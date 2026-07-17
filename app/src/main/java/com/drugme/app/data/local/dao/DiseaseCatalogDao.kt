package com.drugme.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drugme.app.data.local.entity.DiseaseCatalogEntity

@Dao
interface DiseaseCatalogDao {

    /**
     * Prefix type-ahead over condition names.
     *
     * Shortest-first, because MeSH names nest: searching "diabetes" should surface
     * "Diabetes Mellitus" above "Diabetes Mellitus, Type 2, Maturity-Onset Diabetes of the
     * Young", and the shorter name is almost always the one a person means.
     */
    @Query(
        """
        SELECT c.* FROM disease_catalog c
        JOIN disease_catalog_fts f ON c.rowid = f.rowid
        WHERE disease_catalog_fts MATCH :query
        ORDER BY LENGTH(c.name) ASC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 25): List<DiseaseCatalogEntity>

    @Query("SELECT * FROM disease_catalog WHERE id = :id")
    suspend fun getById(id: String): DiseaseCatalogEntity?

    @Query("SELECT COUNT(*) FROM disease_catalog")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DiseaseCatalogEntity>)

    @Query("DELETE FROM disease_catalog")
    suspend fun clear()
}
