/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=8 sts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/*
 * Double hashing implementation.
 */
#include <new>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "pldhash.h"
#include "mozilla/HashFunctions.h"
#include "mozilla/MathAlgorithms.h"
#include "nsDebug.h"     /* for PR_ASSERT */
#include "nsAlgorithm.h"
#include "mozilla/Likely.h"
#include "mozilla/MemoryReporting.h"
#include "mozilla/ChaosMode.h"

using namespace mozilla;

#ifdef DEBUG

class AutoReadOp
{
  Checker& mChk;
public:
  explicit AutoReadOp(Checker& aChk) : mChk(aChk) { mChk.StartReadOp(); }
  ~AutoReadOp() { mChk.EndReadOp(); }
};

class AutoWriteOp
{
  Checker& mChk;
public:
  explicit AutoWriteOp(Checker& aChk) : mChk(aChk) { mChk.StartWriteOp(); }
  ~AutoWriteOp() { mChk.EndWriteOp(); }
};

class AutoIteratorRemovalOp
{
  Checker& mChk;
public:
  explicit AutoIteratorRemovalOp(Checker& aChk)
    : mChk(aChk)
  {
    mChk.StartIteratorRemovalOp();
  }
  ~AutoIteratorRemovalOp() { mChk.EndIteratorRemovalOp(); }
};

class AutoDestructorOp
{
  Checker& mChk;
public:
  explicit AutoDestructorOp(Checker& aChk)
    : mChk(aChk)
  {
    mChk.StartDestructorOp();
  }
  ~AutoDestructorOp() { mChk.EndDestructorOp(); }
};

#endif

PLDHashNumber
PL_DHashStringKey(PLDHashTable* aTable, const void* aKey)
{
  return HashString(static_cast<const char*>(aKey));
}

PLDHashNumber
PL_DHashVoidPtrKeyStub(PLDHashTable* aTable, const void* aKey)
{
  return (PLDHashNumber)(ptrdiff_t)aKey >> 2;
}

bool
PL_DHashMatchEntryStub(PLDHashTable* aTable,
                       const PLDHashEntryHdr* aEntry,
                       const void* aKey)
{
  const PLDHashEntryStub* stub = (const PLDHashEntryStub*)aEntry;

  return stub->key == aKey;
}

bool
PL_DHashMatchStringKey(PLDHashTable* aTable,
                       const PLDHashEntryHdr* aEntry,
                       const void* aKey)
{
  const PLDHashEntryStub* stub = (const PLDHashEntryStub*)aEntry;

  /* XXX tolerate null keys on account of sloppy Mozilla callers. */
  return stub->key == aKey ||
         (stub->key && aKey &&
          strcmp((const char*)stub->key, (const char*)aKey) == 0);
}

MOZ_ALWAYS_INLINE void
PLDHashTable::MoveEntryStub(const PLDHashEntryHdr* aFrom,
                            PLDHashEntryHdr* aTo)
{
  memcpy(aTo, aFrom, mEntrySize);
}

void
PL_DHashMoveEntryStub(PLDHashTable* aTable,
                      const PLDHashEntryHdr* aFrom,
                      PLDHashEntryHdr* aTo)
{
  aTable->MoveEntryStub(aFrom, aTo);
}

MOZ_ALWAYS_INLINE void
PLDHashTable::ClearEntryStub(PLDHashEntryHdr* aEntry)
{
  memset(aEntry, 0, mEntrySize);
}

void
PL_DHashClearEntryStub(PLDHashTable* aTable, PLDHashEntryHdr* aEntry)
{
  aTable->ClearEntryStub(aEntry);
}

static const PLDHashTableOps stub_ops = {
  PL_DHashVoidPtrKeyStub,
  PL_DHashMatchEntryStub,
  PL_DHashMoveEntryStub,
  PL_DHashClearEntryStub,
  nullptr
};

const PLDHashTableOps*
PL_DHashGetStubOps(void)
{
  return &stub_ops;
}

static bool
SizeOfEntryStore(uint32_t aCapacity, uint32_t aEntrySize, uint32_t* aNbytes)
{
  uint64_t nbytes64 = uint64_t(aCapacity) * uint64_t(aEntrySize);
  *aNbytes = aCapacity * aEntrySize;
  return uint64_t(*aNbytes) == nbytes64;   // returns false on overflow
}

/*
 * Compute max and min load numbers (entry counts).  We have a secondary max
 * that allows us to overload a table reasonably if it cannot be grown further
 * (i.e. if ChangeTable() fails).  The table slows down drastically if the
 * secondary max is too close to 1, but 0.96875 gives only a slight slowdown
 * while allowing 1.3x more elements.
 */
static inline uint32_t
MaxLoad(uint32_t aCapacity)
{
  return aCapacity - (aCapacity >> 2);  // == aCapacity * 0.75
}
static inline uint32_t
MaxLoadOnGrowthFailure(uint32_t aCapacity)
{
  return aCapacity - (aCapacity >> 5);  // == aCapacity * 0.96875
}
static inline uint32_t
MinLoad(uint32_t aCapacity)
{
  return aCapacity >> 2;                // == aCapacity * 0.25
}

// Compute the minimum capacity (and the Log2 of that capacity) for a table
// containing |aLength| elements while respecting the following contraints:
// - table must be at most 75% full;
// - capacity must be a power of two;
// - capacity cannot be too small.
static inline void
BestCapacity(uint32_t aLength, uint32_t* aCapacityOut,
             uint32_t* aLog2CapacityOut)
{
  // Compute the smallest capacity allowing |aLength| elements to be inserted
  // without rehashing.
  uint32_t capacity = (aLength * 4 + (3 - 1)) / 3; // == ceil(aLength * 4 / 3)
  if (capacity < PLDHashTable::kMinCapacity) {
    capacity = PLDHashTable::kMinCapacity;
  }

  // Round up capacity to next power-of-two.
  uint32_t log2 = CeilingLog2(capacity);
  capacity = 1u << log2;
  MOZ_ASSERT(capacity <= PLDHashTable::kMaxCapacity);

  *aCapacityOut = capacity;
  *aLog2CapacityOut = log2;
}

/* static */ MOZ_ALWAYS_INLINE uint32_t
PLDHashTable::HashShift(uint32_t aEntrySize, uint32_t aLength)
{
  if (aLength > kMaxInitialLength) {
    MOZ_CRASH("Initial length is too large");
  }

  uint32_t capacity, log2;
  BestCapacity(aLength, &capacity, &log2);

  uint32_t nbytes;
  if (!SizeOfEntryStore(capacity, aEntrySize, &nbytes)) {
    MOZ_CRASH("Initial entry store size is too large");
  }

  // Compute the hashShift value.
  return kHashBits - log2;
}

PLDHashTable::PLDHashTable(const PLDHashTableOps* aOps, uint32_t aEntrySize,
                           uint32_t aLength)
  : mOps(aOps)
  , mHashShift(HashShift(aEntrySize, aLength))
  , mEntrySize(aEntrySize)
  , mEntryCount(0)
  , mRemovedCount(0)
  , mGeneration(0)
  , mEntryStore(nullptr)
#ifdef DEBUG
  , mChecker()
#endif
{
}

PLDHashTable&
PLDHashTable::operator=(PLDHashTable&& aOther)
{
  if (this == &aOther) {
    return *this;
  }

  // Destruct |this|.
  this->~PLDHashTable();

  // |mOps| and |mEntrySize| are const so we can't assign them. Instead, we
  // require that they are equal. The justification for this is that they're
  // conceptually part of the type -- indeed, if PLDHashTable was a templated
  // type like nsTHashtable, they *would* be part of the type -- so it only
  // makes sense to assign in cases where they match.
  MOZ_RELEASE_ASSERT(mOps == aOther.mOps);
  MOZ_RELEASE_ASSERT(mEntrySize == aOther.mEntrySize);

  // Move non-const pieces over.
  mHashShift = Move(aOther.mHashShift);
  mEntryCount = Move(aOther.mEntryCount);
  mRemovedCount = Move(aOther.mRemovedCount);
  mGeneration = Move(aOther.mGeneration);
  mEntryStore = Move(aOther.mEntryStore);
#ifdef DEBUG
  mChecker = Move(aOther.mChecker);
#endif

  // Clear up |aOther| so its destruction will be a no-op.
  {
#ifdef DEBUG
    AutoDestructorOp op(mChecker);
#endif
    aOther.mEntryStore = nullptr;
  }

  return *this;
}

PLDHashNumber
PLDHashTable::Hash1(PLDHashNumber aHash0)
{
  return aHash0 >> mHashShift;
}

// Double hashing needs the second hash code to be relatively prime to table
// size, so we simply make hash2 odd.
void
PLDHashTable::Hash2(PLDHashNumber aHash,
                    uint32_t& aHash2Out, uint32_t& aSizeMaskOut)
{
  uint32_t sizeLog2 = kHashBits - mHashShift;
  aHash2Out = ((aHash << sizeLog2) >> mHashShift) | 1;
  aSizeMaskOut = (PLDHashNumber(1) << sizeLog2) - 1;
}

/*
 * Reserve mKeyHash 0 for free entries and 1 for removed-entry sentinels.  Note
 * that a removed-entry sentinel need be stored only if the removed entry had
 * a colliding entry added after it.  Therefore we can use 1 as the collision
 * flag in addition to the removed-entry sentinel value.  Multiplicative hash
 * uses the high order bits of mKeyHash, so this least-significant reservation
 * should not hurt the hash function's effectiveness much.
 */

/* static */ MOZ_ALWAYS_INLINE bool
PLDHashTable::EntryIsFree(PLDHashEntryHdr* aEntry)
{
  return aEntry->mKeyHash == 0;
}
/* static */ MOZ_ALWAYS_INLINE bool
PLDHashTable::EntryIsRemoved(PLDHashEntryHdr* aEntry)
{
  return aEntry->mKeyHash == 1;
}
/* static */ MOZ_ALWAYS_INLINE bool
PLDHashTable::EntryIsLive(PLDHashEntryHdr* aEntry)
{
  return aEntry->mKeyHash >= 2;
}

/* static */ MOZ_ALWAYS_INLINE void
PLDHashTable::MarkEntryFree(PLDHashEntryHdr* aEntry)
{
  aEntry->mKeyHash = 0;
}
/* static */ MOZ_ALWAYS_INLINE void
PLDHashTable::MarkEntryRemoved(PLDHashEntryHdr* aEntry)
{
  aEntry->mKeyHash = 1;
}

/* Match an entry's mKeyHash against an unstored one computed from a key. */
/* static */ bool
PLDHashTable::MatchEntryKeyhash(PLDHashEntryHdr* aEntry, PLDHashNumber aKeyHash)
{
  return (aEntry->mKeyHash & ~kCollisionFlag) == aKeyHash;
}

/* Compute the address of the indexed entry in table. */
PLDHashEntryHdr*
PLDHashTable::AddressEntry(uint32_t aIndex)
{
  return reinterpret_cast<PLDHashEntryHdr*>(mEntryStore + aIndex * mEntrySize);
}

PLDHashTable::~PLDHashTable()
{
#ifdef DEBUG
  AutoDestructorOp op(mChecker);
#endif

  if (!mEntryStore) {
    return;
  }

  /* Clear any remaining live entries. */
  char* entryAddr = mEntryStore;
  char* entryLimit = entryAddr + Capacity() * mEntrySize;
  while (entryAddr < entryLimit) {
    PLDHashEntryHdr* entry = (PLDHashEntryHdr*)entryAddr;
    if (EntryIsLive(entry)) {
      mOps->clearEntry(this, entry);
    }
    entryAddr += mEntrySize;
  }

  /* Free entry storage last. */
  free(mEntryStore);
  mEntryStore = nullptr;
}

void
PLDHashTable::ClearAndPrepareForLength(uint32_t aLength)
{
  // Get these values before the destructor clobbers them.
  const PLDHashTableOps* ops = mOps;
  uint32_t entrySize = mEntrySize;

  this->~PLDHashTable();
  new (this) PLDHashTable(ops, entrySize, aLength);
}

void
PLDHashTable::Clear()
{
  ClearAndPrepareForLength(kDefaultInitialLength);
}

// If |IsAdd| is true, the return value is always non-null and it may be a
// previously-removed entry. If |IsAdd| is false, the return value is null on a
// miss, and will never be a previously-removed entry on a hit. This
// distinction is a bit grotty but this function is hot enough that these
// differences are worthwhile.
template <PLDHashTable::SearchReason Reason>
PLDHashEntryHdr* PL_DHASH_FASTCALL
PLDHashTable::SearchTable(const void* aKey, PLDHashNumber aKeyHash)
{
  MOZ_ASSERT(mEntryStore);
  NS_ASSERTION(!(aKeyHash & kCollisionFlag),
               "!(aKeyHash & kCollisionFlag)");

  /* Compute the primary hash address. */
  PLDHashNumber hash1 = Hash1(aKeyHash);
  PLDHashEntryHdr* entry = AddressEntry(hash1);

  /* Miss: return space for a new entry. */
  if (EntryIsFree(entry)) {
    return (Reason == ForAdd) ? entry : nullptr;
  }

  /* Hit: return entry. */
  PLDHashMatchEntry matchEntry = mOps->matchEntry;
  if (MatchEntryKeyhash(entry, aKeyHash) &&
      matchEntry(this, entry, aKey)) {
    return entry;
  }

  /* Collision: double hash. */
  PLDHashNumber hash2;
  uint32_t sizeMask;
  Hash2(aKeyHash, hash2, sizeMask);

  /*
   * Save the first removed entry pointer so Add() can recycle it. (Only used
   * if Reason==ForAdd.)
   */
  PLDHashEntryHdr* firstRemoved = nullptr;

  for (;;) {
    if (Reason == ForAdd) {
      if (MOZ_UNLIKELY(EntryIsRemoved(entry))) {
        if (!firstRemoved) {
          firstRemoved = entry;
        }
      } else {
        entry->mKeyHash |= kCollisionFlag;
      }
    }

    hash1 -= hash2;
    hash1 &= sizeMask;

    entry = AddressEntry(hash1);
    if (EntryIsFree(entry)) {
      return (Reason == ForAdd) ? (firstRemoved ? firstRemoved : entry)
                                : nullptr;
    }

    if (MatchEntryKeyhash(entry, aKeyHash) &&
        matchEntry(this, entry, aKey)) {
      return entry;
    }
  }

  /* NOTREACHED */
  return nullptr;
}

/*
 * This is a copy of SearchTable, used by ChangeTable, hardcoded to
 *   1. assume |aIsAdd| is true,
 *   2. assume that |aKey| will never match an existing entry, and
 *   3. assume that no entries have been removed from the current table
 *      structure.
 * Avoiding the need for |aKey| means we can avoid needing a way to map
 * entries to keys, which means callers can use complex key types more
 * easily.
 */
PLDHashEntryHdr* PL_DHASH_FASTCALL
PLDHashTable::FindFreeEntry(PLDHashNumber aKeyHash)
{
  MOZ_ASSERT(mEntryStore);
  NS_ASSERTION(!(aKeyHash & kCollisionFlag),
               "!(aKeyHash & kCollisionFlag)");

  /* Compute the primary hash address. */
  PLDHashNumber hash1 = Hash1(aKeyHash);
  PLDHashEntryHdr* entry = AddressEntry(hash1);

  /* Miss: return space for a new entry. */
  if (EntryIsFree(entry)) {
    return entry;
  }

  /* Collision: double hash. */
  PLDHashNumber hash2;
  uint32_t sizeMask;
  Hash2(aKeyHash, hash2, sizeMask);

  for (;;) {
    NS_ASSERTION(!EntryIsRemoved(entry),
                 "!EntryIsRemoved(entry)");
    entry->mKeyHash |= kCollisionFlag;

    hash1 -= hash2;
    hash1 &= sizeMask;

    entry = AddressEntry(hash1);
    if (EntryIsFree(entry)) {
      return entry;
    }
  }

  /* NOTREACHED */
  return nullptr;
}

bool
PLDHashTable::ChangeTable(int32_t aDeltaLog2)
{
  MOZ_ASSERT(mEntryStore);

  /* Look, but don't touch, until we succeed in getting new entry store. */
  int32_t oldLog2 = kHashBits - mHashShift;
  int32_t newLog2 = oldLog2 + aDeltaLog2;
  uint32_t newCapacity = 1u << newLog2;
  if (newCapacity > kMaxCapacity) {
    return false;
  }

  uint32_t nbytes;
  if (!SizeOfEntryStore(newCapacity, mEntrySize, &nbytes)) {
    return false;   // overflowed
  }

  char* newEntryStore = (char*)malloc(nbytes);
  if (!newEntryStore) {
    return false;
  }

  /* We can't fail from here on, so update table parameters. */
  mHashShift = kHashBits - newLog2;
  mRemovedCount = 0;
  mGeneration++;

  /* Assign the new entry store to table. */
  memset(newEntryStore, 0, nbytes);
  char* oldEntryStore;
  char* oldEntryAddr;
  oldEntryAddr = oldEntryStore = mEntryStore;
  mEntryStore = newEntryStore;
  PLDHashMoveEntry moveEntry = mOps->moveEntry;

  /* Copy only live entries, leaving removed ones behind. */
  uint32_t oldCapacity = 1u << oldLog2;
  for (uint32_t i = 0; i < oldCapacity; ++i) {
    PLDHashEntryHdr* oldEntry = (PLDHashEntryHdr*)oldEntryAddr;
    if (EntryIsLive(oldEntry)) {
      oldEntry->mKeyHash &= ~kCollisionFlag;
      PLDHashEntryHdr* newEntry = FindFreeEntry(oldEntry->mKeyHash);
      NS_ASSERTION(EntryIsFree(newEntry), "EntryIsFree(newEntry)");
      moveEntry(this, oldEntry, newEntry);
      newEntry->mKeyHash = oldEntry->mKeyHash;
    }
    oldEntryAddr += mEntrySize;
  }

  free(oldEntryStore);
  return true;
}

MOZ_ALWAYS_INLINE PLDHashNumber
PLDHashTable::ComputeKeyHash(const void* aKey)
{
  MOZ_ASSERT(mEntryStore);

  PLDHashNumber keyHash = mOps->hashKey(this, aKey);
  keyHash *= kGoldenRatio;

  /* Avoid 0 and 1 hash codes, they indicate free and removed entries. */
  if (keyHash < 2) {
    keyHash -= 2;
  }
  keyHash &= ~kCollisionFlag;

  return keyHash;
}

MOZ_ALWAYS_INLINE PLDHashEntryHdr*
PLDHashTable::Search(const void* aKey)
{
#ifdef DEBUG
  AutoReadOp op(mChecker);
#endif

  PLDHashEntryHdr* entry =
    mEntryStore ? SearchTable<ForSearchOrRemove>(aKey, ComputeKeyHash(aKey))
                : nullptr;

  return entry;
}

MOZ_ALWAYS_INLINE PLDHashEntryHdr*
PLDHashTable::Add(const void* aKey, const mozilla::fallible_t&)
{
#ifdef DEBUG
  AutoWriteOp op(mChecker);
#endif

  // Allocate the entry storage if it hasn't already been allocated.
  if (!mEntryStore) {
    uint32_t nbytes;
    // We already checked this in the constructor, so it must still be true.
    MOZ_RELEASE_ASSERT(SizeOfEntryStore(CapacityFromHashShift(), mEntrySize,
                                        &nbytes));
    mEntryStore = (char*)malloc(nbytes);
    if (!mEntryStore) {
      return nullptr;
    }
    memset(mEntryStore, 0, nbytes);
  }

  /*
   * If alpha is >= .75, grow or compress the table.  If aKey is already
   * in the table, we may grow once more than necessary, but only if we
   * are on the edge of being overloaded.
   */
  uint32_t capacity = Capacity();
  if (mEntryCount + mRemovedCount >= MaxLoad(capacity)) {
    /* Compress if a quarter or more of all entries are removed. */
    int deltaLog2;
    if (mRemovedCount >= capacity >> 2) {
      deltaLog2 = 0;
    } else {
      deltaLog2 = 1;
    }

    /*
     * Grow or compress the table.  If ChangeTable() fails, allow
     * overloading up to the secondary max.  Once we hit the secondary
     * max, return null.
     */
    if (!ChangeTable(deltaLog2) &&
        mEntryCount + mRemovedCount >= MaxLoadOnGrowthFailure(capacity)) {
      return nullptr;
    }
  }

  /*
   * Look for entry after possibly growing, so we don't have to add it,
   * then skip it while growing the table and re-add it after.
   */
  PLDHashNumber keyHash = ComputeKeyHash(aKey);
  PLDHashEntryHdr* entry = SearchTable<ForAdd>(aKey, keyHash);
  if (!EntryIsLive(entry)) {
    /* Initialize the entry, indicating that it's no longer free. */
    if (EntryIsRemoved(entry)) {
      mRemovedCount--;
      keyHash |= kCollisionFlag;
    }
    if (mOps->initEntry) {
      mOps->initEntry(entry, aKey);
    }
    entry->mKeyHash = keyHash;
    mEntryCount++;
  }

  return entry;
}

MOZ_ALWAYS_INLINE PLDHashEntryHdr*
PLDHashTable::Add(const void* aKey)
{
  PLDHashEntryHdr* entry = Add(aKey, fallible);
  if (!entry) {
    if (!mEntryStore) {
      // We OOM'd while allocating the initial entry storage.
      uint32_t nbytes;
      (void) SizeOfEntryStore(CapacityFromHashShift(), mEntrySize, &nbytes);
      NS_ABORT_OOM(nbytes);
    } else {
      // We failed to resize the existing entry storage, either due to OOM or
      // because we exceeded the maximum table capacity or size; report it as
      // an OOM. The multiplication by 2 gets us the size we tried to allocate,
      // which is double the current size.
      NS_ABORT_OOM(2 * EntrySize() * EntryCount());
    }
  }
  return entry;
}

MOZ_ALWAYS_INLINE void
PLDHashTable::Remove(const void* aKey)
{
#ifdef DEBUG
  AutoWriteOp op(mChecker);
#endif

  PLDHashEntryHdr* entry =
    mEntryStore ? SearchTable<ForSearchOrRemove>(aKey, ComputeKeyHash(aKey))
                : nullptr;
  if (entry) {
    /* Clear this entry and mark it as "removed". */
    PL_DHashTableRawRemove(this, entry);

    /* Shrink if alpha is <= .25 and the table isn't too small already. */
    uint32_t capacity = Capacity();
    if (capacity > kMinCapacity &&
        mEntryCount <= MinLoad(capacity)) {
      (void) ChangeTable(-1);
    }
  }
}

PLDHashEntryHdr* PL_DHASH_FASTCALL
PL_DHashTableSearch(PLDHashTable* aTable, const void* aKey)
{
  return aTable->Search(aKey);
}

PLDHashEntryHdr* PL_DHASH_FASTCALL
PL_DHashTableAdd(PLDHashTable* aTable, const void* aKey,
                 const fallible_t& aFallible)
{
  return aTable->Add(aKey, aFallible);
}

PLDHashEntryHdr* PL_DHASH_FASTCALL
PL_DHashTableAdd(PLDHashTable* aTable, const void* aKey)
{
  return aTable->Add(aKey);
}

void PL_DHASH_FASTCALL
PL_DHashTableRemove(PLDHashTable* aTable, const void* aKey)
{
  aTable->Remove(aKey);
}

MOZ_ALWAYS_INLINE void
PLDHashTable::RawRemove(PLDHashEntryHdr* aEntry)
{
  // Unfortunately, we can only do weak checking here. That's because
  // RawRemove() can be called legitimately while an Enumerate() call is
  // active, which doesn't fit well into how Checker's mState variable works.
  MOZ_ASSERT(mChecker.IsWritable());

  MOZ_ASSERT(mEntryStore);

  NS_ASSERTION(EntryIsLive(aEntry), "EntryIsLive(aEntry)");

  /* Load keyHash first in case clearEntry() goofs it. */
  PLDHashNumber keyHash = aEntry->mKeyHash;
  mOps->clearEntry(this, aEntry);
  if (keyHash & kCollisionFlag) {
    MarkEntryRemoved(aEntry);
    mRemovedCount++;
  } else {
    MarkEntryFree(aEntry);
  }
  mEntryCount--;
}

void
PL_DHashTableRawRemove(PLDHashTable* aTable, PLDHashEntryHdr* aEntry)
{
  aTable->RawRemove(aEntry);
}

// Shrink or compress if a quarter or more of all entries are removed, or if the
// table is underloaded according to the minimum alpha, and is not minimal-size
// already.
void
PLDHashTable::ShrinkIfAppropriate()
{
  uint32_t capacity = Capacity();
  if (mRemovedCount >= capacity >> 2 ||
      (capacity > kMinCapacity && mEntryCount <= MinLoad(capacity))) {
    uint32_t log2;
    BestCapacity(mEntryCount, &capacity, &log2);

    int32_t deltaLog2 = log2 - (kHashBits - mHashShift);
    MOZ_ASSERT(deltaLog2 <= 0);

    (void) ChangeTable(deltaLog2);
  }
}

MOZ_ALWAYS_INLINE size_t
PLDHashTable::SizeOfExcludingThis(
    PLDHashSizeOfEntryExcludingThisFun aSizeOfEntryExcludingThis,
    MallocSizeOf aMallocSizeOf, void* aArg /* = nullptr */) const
{
#ifdef DEBUG
  AutoReadOp op(mChecker);
#endif

  if (!mEntryStore) {
    return 0;
  }

  size_t n = aMallocSizeOf(mEntryStore);
  if (aSizeOfEntryExcludingThis) {
    for (auto iter = ConstIter(); !iter.Done(); iter.Next()) {
      n += aSizeOfEntryExcludingThis(iter.Get(), aMallocSizeOf, aArg);
    }
  }

  return n;
}

MOZ_ALWAYS_INLINE size_t
PLDHashTable::SizeOfIncludingThis(
    PLDHashSizeOfEntryExcludingThisFun aSizeOfEntryExcludingThis,
    MallocSizeOf aMallocSizeOf, void* aArg /* = nullptr */) const
{
  return aMallocSizeOf(this) +
         SizeOfExcludingThis(aSizeOfEntryExcludingThis, aMallocSizeOf, aArg);
}

size_t
PL_DHashTableSizeOfExcludingThis(
    const PLDHashTable* aTable,
    PLDHashSizeOfEntryExcludingThisFun aSizeOfEntryExcludingThis,
    MallocSizeOf aMallocSizeOf, void* aArg /* = nullptr */)
{
  return aTable->SizeOfExcludingThis(aSizeOfEntryExcludingThis,
                                     aMallocSizeOf, aArg);
}

size_t
PL_DHashTableSizeOfIncludingThis(
    const PLDHashTable* aTable,
    PLDHashSizeOfEntryExcludingThisFun aSizeOfEntryExcludingThis,
    MallocSizeOf aMallocSizeOf, void* aArg /* = nullptr */)
{
  return aTable->SizeOfIncludingThis(aSizeOfEntryExcludingThis,
                                     aMallocSizeOf, aArg);
}

PLDHashTable::Iterator::Iterator(Iterator&& aOther)
  : mTable(aOther.mTable)
  , mStart(aOther.mStart)
  , mLimit(aOther.mLimit)
  , mCurrent(aOther.mCurrent)
  , mNexts(aOther.mNexts)
  , mNextsLimit(aOther.mNextsLimit)
  , mHaveRemoved(aOther.mHaveRemoved)
{
  // No need to change |mChecker| here.
  aOther.mTable = nullptr;
  aOther.mStart = nullptr;
  aOther.mLimit = nullptr;
  aOther.mCurrent = nullptr;
  aOther.mNexts = 0;
  aOther.mNextsLimit = 0;
  aOther.mHaveRemoved = false;
}

PLDHashTable::Iterator::Iterator(PLDHashTable* aTable)
  : mTable(aTable)
  , mStart(mTable->mEntryStore)
  , mLimit(mTable->mEntryStore + mTable->Capacity() * mTable->mEntrySize)
  , mCurrent(mTable->mEntryStore)
  , mNexts(0)
  , mNextsLimit(mTable->EntryCount())
  , mHaveRemoved(false)
{
#ifdef DEBUG
  mTable->mChecker.StartReadOp();
#endif

  if (ChaosMode::isActive(ChaosFeature::HashTableIteration) &&
      mTable->Capacity() > 0) {
    // Start iterating at a random entry. It would be even more chaotic to
    // iterate in fully random order, but that's harder.
    mCurrent += ChaosMode::randomUint32LessThan(mTable->Capacity()) *
                mTable->mEntrySize;
  }

  // Advance to the first live entry, if there is one.
  if (!Done()) {
    while (IsOnNonLiveEntry()) {
      MoveToNextEntry();
    }
  }
}

PLDHashTable::Iterator::~Iterator()
{
  if (mTable) {
    if (mHaveRemoved) {
      mTable->ShrinkIfAppropriate();
    }
#ifdef DEBUG
    mTable->mChecker.EndReadOp();
#endif
  }
}

bool
PLDHashTable::Iterator::Done() const
{
  return mNexts == mNextsLimit;
}

MOZ_ALWAYS_INLINE bool
PLDHashTable::Iterator::IsOnNonLiveEntry() const
{
  MOZ_ASSERT(!Done());
  return !EntryIsLive(reinterpret_cast<PLDHashEntryHdr*>(mCurrent));
}

MOZ_ALWAYS_INLINE void
PLDHashTable::Iterator::MoveToNextEntry()
{
  mCurrent += mTable->mEntrySize;
  if (mCurrent == mLimit) {
    mCurrent = mStart;  // Wrap-around. Possible due to Chaos Mode.
  }
}

PLDHashEntryHdr*
PLDHashTable::Iterator::Get() const
{
  MOZ_ASSERT(!Done());

  PLDHashEntryHdr* entry = reinterpret_cast<PLDHashEntryHdr*>(mCurrent);
  MOZ_ASSERT(EntryIsLive(entry));
  return entry;
}

void
PLDHashTable::Iterator::Next()
{
  MOZ_ASSERT(!Done());

  mNexts++;

  // Advance to the next live entry, if there is one.
  if (!Done()) {
    do {
      MoveToNextEntry();
    } while (IsOnNonLiveEntry());
  }
}

void
PLDHashTable::Iterator::Remove()
{
  // This cast is needed for the same reason as the one in the destructor.
  mTable->RawRemove(Get());
  mHaveRemoved = true;
}

#ifdef DEBUG
MOZ_ALWAYS_INLINE void
PLDHashTable::MarkImmutable()
{
  mChecker.SetNonWritable();
}

void
PL_DHashMarkTableImmutable(PLDHashTable* aTable)
{
  aTable->MarkImmutable();
}
#endif

