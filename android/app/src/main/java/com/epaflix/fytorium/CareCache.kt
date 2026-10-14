package com.epaflix.fytorium

import androidx.room.*

@Entity(primaryKeys = ["memberId", "resource"])
data class CachedCare(val memberId: String, val resource: String, val json: String, val refreshedAt: Long)
@Dao
interface CareDao {
    @Query("SELECT * FROM CachedCare WHERE resource = 'identity' LIMIT 1") fun identity(): CachedCare?
    @Query("SELECT * FROM CachedCare WHERE memberId = :member AND resource = :resource") fun get(member: String, resource: String): CachedCare?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(rows: List<CachedCare>)
    @Query("DELETE FROM CachedCare") fun clear()
}
@Database(entities = [CachedCare::class], version = 1, exportSchema = true)
abstract class CareDatabase : RoomDatabase() { abstract fun care(): CareDao }

// JSON snapshots preserve server groups and provenance; every row belongs to one member.
class CareCache(private val db: CareDatabase) {
    fun member(): Member? = db.care().identity()?.let { parseMember(it.json) }
    fun get(member: String, resource: String): CachedCare? = db.care().get(member, resource)
    fun bind(identity: String) {
        val member = parseMember(identity)
        db.runInTransaction {
            if (member() != member) db.care().clear()
            db.care().put(listOf(CachedCare(member.id, "identity", identity, 0)))
        }
    }
    fun save(member: String, snapshots: Map<String, String>, time: Long) {
        db.runInTransaction {
            // A response can outlive the member binding that started it.
            if (member()?.id != member) return@runInTransaction
            db.care().put(snapshots.map { (key, json) -> CachedCare(member, key, json, time) })
        }
    }
    fun wipe() = db.runInTransaction { db.care().clear() }
}
