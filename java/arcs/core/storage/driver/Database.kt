/*
 * Copyright 2020 Google LLC.
 *
 * This code may only be used under the BSD style license found at
 * http://polymer.github.io/LICENSE.txt
 *
 * Code distributed by Google as part of this project is also subject to an additional IP rights
 * grant found at
 * http://polymer.github.io/PATENTS.txt
 */

package arcs.core.storage.driver

import androidx.annotation.VisibleForTesting
import arcs.core.common.Referencable
import arcs.core.crdt.CrdtEntity
import arcs.core.crdt.CrdtSet
import arcs.core.crdt.CrdtSingleton
import arcs.core.crdt.extension.toCrdtEntityData
import arcs.core.data.RawEntity
import arcs.core.data.Schema
import arcs.core.data.util.ReferencableList
import arcs.core.storage.Driver
import arcs.core.storage.DriverFactory
import arcs.core.storage.DriverProvider
import arcs.core.storage.Reference
import arcs.core.storage.StorageKey
import arcs.core.storage.database.Database
import arcs.core.storage.database.DatabaseClient
import arcs.core.storage.database.DatabaseData
import arcs.core.storage.database.DatabaseManager
import arcs.core.storage.database.ReferenceWithVersion
import arcs.core.storage.keys.DatabaseStorageKey
import arcs.core.storage.referencemode.toCrdtSetData
import arcs.core.storage.referencemode.toCrdtSingletonData
import arcs.core.type.Type
import arcs.core.util.Random
import arcs.core.util.TaggedLog
import kotlin.reflect.KClass

/** [DriverProvider] which provides a [DatabaseDriver]. */
object DatabaseDriverProvider : DriverProvider {
    /**
     * Whether or not the [DatabaseDriverProvider] has been configured with a [DatabaseManager] and
     * a schema lookup function.
     */
    val isConfigured: Boolean
        get() = _manager != null

    private var _manager: DatabaseManager? = null

    /** The configured [DatabaseManager]. */
    val manager: DatabaseManager
        get() = requireNotNull(_manager) { ERROR_MESSAGE_CONFIGURE_NOT_CALLED }

    /**
     * Function which will be used to determine, at runtime, which [Schema] to associate with its
     * hash value embedded in a [DatabaseStorageKey].
     */
    private var schemaLookup: (String) -> Schema? = {
        throw IllegalStateException(ERROR_MESSAGE_CONFIGURE_NOT_CALLED)
    }

    override fun willSupport(storageKey: StorageKey): Boolean =
        storageKey is DatabaseStorageKey && schemaLookup(storageKey.entitySchemaHash) != null

    override suspend fun <Data : Any> getDriver(
        storageKey: StorageKey,
        dataClass: KClass<Data>,
        type: Type
    ): Driver<Data> {
        val databaseKey = requireNotNull(storageKey as? DatabaseStorageKey) {
            "Unsupported StorageKey: $storageKey for DatabaseDriverProvider"
        }
        requireNotNull(schemaLookup(databaseKey.entitySchemaHash)) {
            "Unsupported DatabaseStorageKey: No Schema found with hash: " +
                databaseKey.entitySchemaHash
        }
        require(
            dataClass == CrdtEntity.Data::class ||
                dataClass == CrdtSet.DataImpl::class ||
                dataClass == CrdtSingleton.DataImpl::class
        ) {
            "Unsupported data type: $dataClass, must be one of: CrdtEntity.Data, " +
                "CrdtSet.DataImpl, or CrdtSingleton.DataImpl"
        }
        return DatabaseDriver(
            databaseKey,
            dataClass,
            schemaLookup,
            manager.getDatabase(databaseKey.dbName, databaseKey is DatabaseStorageKey.Persistent)
        ).register()
    }

    override suspend fun removeAllEntities() {
        manager.removeAllEntities()
    }

    override suspend fun removeEntitiesCreatedBetween(startTimeMillis: Long, endTimeMillis: Long) {
        manager.removeEntitiesCreatedBetween(startTimeMillis, endTimeMillis)
    }

    override suspend fun getEntitiesCount(inMemory: Boolean): Long {
        return manager.getEntitiesCount(!inMemory)
    }

    override suspend fun getStorageSize(inMemory: Boolean): Long {
        return manager.getStorageSize(!inMemory)
    }

    override suspend fun isStorageTooLarge(): Boolean {
        return manager.isStorageTooLarge()
    }

    /**
     * Configures the [DatabaseDriverProvider] with the given [schemaLookup] and registers it
     * with the [DriverFactory].
     */
    fun configure(databaseManager: DatabaseManager, schemaLookup: (String) -> Schema?) = apply {
        this._manager = databaseManager
        this.schemaLookup = schemaLookup
        DriverFactory.register(this)
    }

    private const val ERROR_MESSAGE_CONFIGURE_NOT_CALLED =
        "DatabaseDriverProvider.configure(databaseFactory, schemaLookup) has not been called"
}

/** [Driver] implementation capable of managing data stored in a SQL database. */
@Suppress("RemoveExplicitTypeArguments")
class DatabaseDriver<Data : Any>(
    override val storageKey: DatabaseStorageKey,
    private val dataClass: KClass<Data>,
    private val schemaLookup: (String) -> Schema?,
    /* internal */
    val database: Database
) : Driver<Data>, DatabaseClient {
    /* internal */ var receiver: (suspend (data: Data, version: Int) -> Unit)? = null
    /* internal */ var clientId: Int = -1

    private val schema: Schema
        get() = checkNotNull(schemaLookup(storageKey.entitySchemaHash)) {
            "Schema not found for hash: ${storageKey.entitySchemaHash}"
        }
    // TODO(#5551): Consider including a hash of the toString info in log prefix.
    private val log = TaggedLog { "DatabaseDriver" }

    override var token: String? = null
        private set

    /* internal */
    suspend fun register(): DatabaseDriver<Data> = apply {
        clientId = database.addClient(this)

        log.debug { "Registered with clientId = $clientId" }
    }

    override suspend fun registerReceiver(
        token: String?,
        receiver: suspend (data: Data, version: Int) -> Unit
    ) {
        this.receiver = receiver
        val (pendingReceiverData, pendingReceiverVersion) = getDatabaseData()

        if (pendingReceiverData == null || pendingReceiverVersion == null) return

        log.verbose {
            """
                registerReceiver($token) - calling receiver(
                    $pendingReceiverData,
                    $pendingReceiverVersion
                )
            """.trimIndent()
        }
        receiver(pendingReceiverData, pendingReceiverVersion)
    }

    override suspend fun close() {
        receiver = null
        database.removeClient(clientId)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun send(data: Data, version: Int): Boolean {
        log.verbose {
            """
                send(
                    $data,
                    $version
                )
            """.trimIndent()
        }

        // Prep the data for storage.
        val databaseData = when (data) {
            is CrdtEntity.Data -> DatabaseData.Entity(
                data.toRawEntity(),
                schema,
                version,
                data.versionMap
            )
            is CrdtSingleton.Data<*> -> {
                val referenceData = requireNotNull(data as? CrdtSingleton.Data<Reference>) {
                    "Data must be CrdtSingleton.Data<Reference>"
                }
                // Use consumerView logic to extract the item from the crdt.
                val id = CrdtSingleton.createWithData(referenceData).consumerView?.id
                val item = id?.let { referenceData.values[it] }
                DatabaseData.Singleton(
                    item?.let { ReferenceWithVersion(it.value, it.versionMap) },
                    schema,
                    version,
                    referenceData.versionMap
                )
            }
            is CrdtSet.Data<*> -> {
                val referenceData = requireNotNull(data as? CrdtSet.Data<Reference>) {
                    "Data must be CrdtSet.Data<Reference>"
                }
                DatabaseData.Collection(
                    referenceData.values.values.map {
                        ReferenceWithVersion(it.value, it.versionMap)
                    }.toSet(),
                    schema,
                    version,
                    referenceData.versionMap
                )
            }
            else -> throw UnsupportedOperationException(
                "Unsupported type for DatabaseDriver: ${data::class}"
            )
        }

        // Store the prepped data.
        return database.insertOrUpdate(storageKey, databaseData, clientId)
    }

    override suspend fun onDatabaseUpdate(
        data: DatabaseData,
        version: Int,
        originatingClientId: Int?
    ) {
        if (originatingClientId == clientId) return

        // Convert the raw DatabaseData into the appropriate CRDT data model
        val actualData = data.toCrdtData<Data>()

        log.verbose {
            """
                onDatabaseUpdate(
                    $data,
                    version: $version,
                    originatingClientId: $originatingClientId
                )
            """.trimIndent()
        }

        // Let the receiver know about it.
        bumpToken()
        receiver?.invoke(actualData, version)
    }

    override suspend fun onDatabaseDelete(originatingClientId: Int?) {
        if (originatingClientId == clientId) return

        val (dbData, dbVersion) = getDatabaseData()

        log.debug { "onDatabaseDelete(originatingClientId: $originatingClientId)" }
        bumpToken()
        if (dbData != null && dbVersion != null) { receiver?.invoke(dbData, dbVersion) }
    }

    override fun toString(): String = "DatabaseDriver($storageKey, $clientId)"

    private fun bumpToken() {
        token = Random.nextInt().toString()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    suspend fun getDatabaseData(): Pair<Data?, Int?> {
        var dataAndVersion: Pair<Data?, Int?> = null to null
        database.get(
            storageKey,
            when (dataClass) {
                CrdtEntity.Data::class -> DatabaseData.Entity::class
                CrdtSingleton.DataImpl::class -> DatabaseData.Singleton::class
                CrdtSet.DataImpl::class -> DatabaseData.Collection::class
                else -> throw IllegalStateException("Illegal dataClass: $dataClass")
            },
            schema
        )?.also {
            dataAndVersion = it.toCrdtData<Data>() to it.databaseVersion
        }
        return dataAndVersion
    }
}

@Suppress("UNCHECKED_CAST")
private fun <Data> DatabaseData.toCrdtData() = when (this) {
    is DatabaseData.Singleton -> value.toCrdtSingletonData(versionMap)
    is DatabaseData.Collection -> values.toCrdtSetData(versionMap)
    is DatabaseData.Entity -> rawEntity.toCrdtEntityData(versionMap) { it.toCrdtEntityReference() }
} as Data

// We represent field data differently at different levels:
// * Users see Entities with language-specific types for fields
// * These get converted to RawEntities with Referencable fields
// * CRDTEntities all have CrdtEntity.Reference typed fields
// * The Database takes a fourth representation
//
// For CrdtEntity structures, Most Referencables are converted to
// strings wrapped in References, and the raw string is the DB Data
// layer. Inline entities and lists, however, wrap the Referencable
// structure in the Reference directly, and then unwrap it again for
// the DB layer (which understands these structures and deals with them).
// We did it this way because we didn't want to try and encode list o
// entity data in a string.
//
// This function deals with going in the opposite direction - that is,
// taking DB Data and converting it into a Reference with the right upstream
// behaviour.
private fun Referencable.toCrdtEntityReference(): CrdtEntity.Reference {
    return when (this) {
        is Reference -> this
        is RawEntity -> CrdtEntity.Reference.wrapReferencable(this)
        is ReferencableList<*> -> CrdtEntity.Reference.wrapReferencable(this)
        else -> CrdtEntity.Reference.buildReference(this)
    }
}
