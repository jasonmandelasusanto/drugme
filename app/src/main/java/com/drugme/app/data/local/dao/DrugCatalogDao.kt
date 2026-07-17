package com.drugme.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drugme.app.data.local.entity.DrugCatalogEntity

@Dao
interface DrugCatalogDao {

    /**
     * Prefix type-ahead over the FTS index.
     *
     * The caller passes an already-sanitised query (see DrugCatalogRepository): FTS MATCH
     * has its own syntax, and passing raw user input straight through lets stray quotes or
     * operators throw mid-keystroke.
     */
    @Query(
        """
        SELECT c.* FROM drug_catalog c
        JOIN drug_catalog_fts f ON c.rowid = f.rowid
        WHERE drug_catalog_fts MATCH :query
        ORDER BY LENGTH(c.name) ASC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 25): List<DrugCatalogEntity>

    @Query("SELECT * FROM drug_catalog WHERE rxcui = :rxcui")
    suspend fun getByRxcui(rxcui: String): DrugCatalogEntity?

    @Query("SELECT COUNT(*) FROM drug_catalog")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DrugCatalogEntity>)

    @Query("DELETE FROM drug_catalog")
    suspend fun clear()
}
