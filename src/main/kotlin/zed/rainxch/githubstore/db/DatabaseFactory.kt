package zed.rainxch.githubstore.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory

object DatabaseFactory {

    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init() {
        val dataSource = hikari()
        Database.connect(dataSource)
        runMigrations()
        log.info("Database initialized successfully")
    }

    private fun hikari(): HikariDataSource {
        val config = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/githubstore")
            username = env("DATABASE_USER", "githubstore")
            password = env("DATABASE_PASSWORD", "githubstore")
            // 12 fits the PgTune-shaped "2 × cores + spindles ≈ 10" rule on
            // a 4-vCPU box that also runs Postgres + Meili. The downstream
            // coldQueryGate(8) and persistenceGate(4) already cap fan-out, so
            // 20 was just letting bursts pile up connections that Postgres
            // couldn't usefully parallelise. Override via DATABASE_POOL_SIZE.
            maximumPoolSize = env("DATABASE_POOL_SIZE", "12").toInt()
            isAutoCommit = false
            connectionTimeout = 5_000
            validationTimeout = 3_000
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }

    // internal (not private) so integration tests can run the real migration
    // set against a throwaway Postgres after connecting Exposed to it.
    internal fun runMigrations() {
        log.info("Running database migrations...")

        // Initial schema check + V1 apply runs in its own transaction so
        // its failure mode is isolated from incremental migrations below.
        transaction {
            val migrationSql = this::class.java.classLoader
                .getResourceAsStream("db/migration/V1__initial_schema.sql")
                ?.bufferedReader()?.readText()
                ?: error("Migration file not found")

            val tablesExist = exec("SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'repos')") { rs ->
                rs.next() && rs.getBoolean(1)
            } ?: false

            if (!tablesExist) {
                log.info("Applying initial schema migration...")
                exec(migrationSql)
                log.info("Schema migration applied successfully")
            } else {
                log.info("Schema already exists, skipping initial migration")
            }
        }

        // Incremental migrations — ONE TRANSACTION PER MIGRATION. The
        // previous "one giant transaction wraps every migration" shape
        // looked atomic but Postgres aborts the whole txn the instant any
        // statement throws — even when the Kotlin layer catches the
        // exception as an "idempotent skip" (42P07 etc.). The next
        // migration then hit sqlState 25P02 "current transaction is
        // aborted, commands ignored", which is exactly how V17 failed in
        // prod after V16's CREATE TABLE threw the expected 42P07.
        //
        // Per-migration transactions: each migration commits or rolls back
        // independently. An idempotent-skip in V16 leaves the Postgres
        // session clean for V17. Loses cross-migration atomicity (a deploy
        // can land in a state where some but not all of the batch
        // applied), but that was an illusion before — exception-on-error
        // already meant the whole txn was usefully dead.
        val migrations = listOf(
            "V2__add_download_count.sql",
            "V3__search_miss_processing.sql",
            "V4__signals_and_search_score.sql",
            "V5__resource_cache.sql",
            "V6__hash_device_id_drop_query_sample.sql",
            "V9__events_indexes_and_repos_indexed_at.sql",
            "V11__device_id_hmac_rehash.sql",
            "V12__signing_fingerprint.sql",
            // V13 drops the telemetry_events table after the pipeline was
            // reverted. V7 (create) and V8 (text->jsonb) are intentionally
            // delisted above so a fresh install never creates the table
            // only for V13 to drop it seconds later.
            "V13__drop_telemetry_events.sql",
            "V14__open_issues_count.sql",
            "V15__license_info.sql",
            "V16__oauth_ephemeral.sql",
            "V17__signing_fingerprint_host.sql",
            "V18__pushed_at.sql",
            "V19__feed_exposure.sql",
            "V20__repo_daily_snapshot_and_archived.sql",
            "V21__repos_daily_stars.sql",
        )
        for (migration in migrations) {
            val rawSql = this::class.java.classLoader
                .getResourceAsStream("db/migration/$migration")
                ?.bufferedReader()?.readText() ?: continue
            val sql = preprocessMigration(migration, rawSql) ?: continue
            try {
                transaction { exec(sql) }
            } catch (e: Exception) {
                val sqlState = (e as? PSQLException)?.sqlState
                    ?: (e.cause as? PSQLException)?.sqlState
                    ?: extractSqlState(e)
                if (sqlState in IGNORABLE_MIGRATION_SQLSTATES) {
                    log.info("Migration $migration: idempotent skip (sqlState=$sqlState)")
                } else {
                    // logback async flush can lose this on JVM exit, so also
                    // splat to stderr (unbuffered) to guarantee the operator
                    // sees the failure in `docker logs` before the container
                    // restart loop masks the cause.
                    System.err.println(
                        "FATAL: migration $migration failed (sqlState=$sqlState): ${e.message}"
                    )
                    throw IllegalStateException(
                        "Migration $migration failed (sqlState=$sqlState): ${e.message}",
                        e,
                    )
                }
            }
        }
    }

    // Walk the exception cause chain for a PSQLException. Exposed wraps
    // statement failures inside ExposedSQLException whose `cause` is the
    // raw PSQLException; deeper layers may double-wrap further, so a
    // recursive walk is more robust than a one-level `e.cause as?` cast.
    private fun extractSqlState(e: Throwable): String? {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 8) {
            (cur as? PSQLException)?.sqlState?.let { return it }
            cur = cur.cause
            depth++
        }
        return null
    }

    // SQLSTATEs that indicate "the thing you're trying to add is already there":
    //   42701 duplicate_column, 42P07 duplicate_table,
    //   42710 duplicate_object, 42P16 invalid_table_definition (covers some
    //   CREATE INDEX IF NOT EXISTS races), 23505 unique_violation (for seed
    //   data INSERT ... ON CONFLICT paths).
    private val IGNORABLE_MIGRATION_SQLSTATES = setOf(
        "42701", "42P07", "42710", "42P16", "23505",
    )

    private fun env(name: String, default: String): String =
        System.getenv(name) ?: default

    // V6 uses a psql-style :'device_id_pepper' variable that Exposed's exec()
    // doesn't bind. We do a one-shot string substitution with the env-supplied
    // pepper here (single-quoted, with embedded single-quotes doubled per SQL
    // rules). If DEVICE_ID_PEPPER is unset, we skip V6 entirely and log loudly
    // so the operator can re-run after setting the env var. The migration is
    // designed to be idempotent: the column drop uses IF EXISTS and the rehash
    // skips rows that already look like 64-hex.
    //
    // QUOTING ASSUMPTION (read before writing a new migration that uses the pepper):
    // This substitution assumes the pepper is used only inside `'...'` single-quoted
    // SQL string literals (the escape rule is to double the single quotes, which we
    // apply above). If a future migration uses the pepper inside `$$...$$`
    // dollar-quoted blocks, `E'...'` extended-string literals, or `COPY` blocks,
    // this substitution is UNSAFE -- the doubled-quote escape is wrong in those
    // contexts and a pepper containing the right characters could break out of
    // its quoting. The safe refactor at that point is to switch to
    // `set_config('app.device_id_pepper', $1, false)` plus
    // `current_setting('app.device_id_pepper')` with a real PreparedStatement bind
    // for $1, instead of string substitution.
    private fun preprocessMigration(name: String, sql: String): String? {
        if (!sql.contains(":'device_id_pepper'")) return sql
        val pepper = System.getenv("DEVICE_ID_PEPPER")?.takeIf { it.isNotBlank() }
        if (pepper == null) {
            log.warn(
                "Migration {}: DEVICE_ID_PEPPER not set, SKIPPING. " +
                    "Set the env var and restart to apply column drop and rehash.",
                name,
            )
            return null
        }
        val escaped = "'" + pepper.replace("'", "''") + "'"
        return sql.replace(":'device_id_pepper'", escaped)
    }
}
