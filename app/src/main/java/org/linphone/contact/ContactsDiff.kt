package org.linphone.contact

// What the friend list holds for a native contact, in plain values, so a newly loaded address
// book can be compared with what's already in the friend list without touching the Core.
// Friend.nativeUri isn't included: the friends database doesn't store it, so it's always empty on
// friends loaded at startup, and the loader fills it in separately.
data class ContactSnapshot(
    val refKey: String,
    val name: String?,
    val organization: String?,
    val starred: Boolean,
    val photo: String?,
    // Order doesn't matter, so callers pass these sorted
    val phoneNumbers: List<String>,
    val addresses: List<String>
)

// The changes needed to turn the friend list's contacts into the newly loaded ones. Writing to the
// friend list is slow (each add or remove costs milliseconds and a database write), so only
// contacts that are new, gone or changed are touched. A changed contact is removed and re-added.
data class ContactsDiff(val toRemove: Set<String>, val toAdd: Set<String>) {
    val isEmpty: Boolean
        get() = toRemove.isEmpty() && toAdd.isEmpty()

    companion object {
        fun compute(existing: List<ContactSnapshot>, incoming: Map<String, ContactSnapshot>): ContactsDiff {
            val existingByKey = HashMap<String, ContactSnapshot>()
            val duplicates = HashSet<String>()
            for (snapshot in existing) {
                if (existingByKey.put(snapshot.refKey, snapshot) != null) {
                    duplicates.add(snapshot.refKey)
                }
            }

            val toRemove = HashSet<String>(duplicates)
            for ((refKey, snapshot) in existingByKey) {
                if (incoming[refKey] != snapshot) toRemove.add(refKey)
            }

            val toAdd = HashSet<String>()
            for ((refKey, snapshot) in incoming) {
                if (refKey in duplicates || existingByKey[refKey] != snapshot) toAdd.add(refKey)
            }

            return ContactsDiff(toRemove, toAdd)
        }
    }
}
