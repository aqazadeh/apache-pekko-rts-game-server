-- ScyllaDB / Cassandra keyspace ve tablo şeması
-- pekko-persistence-cassandra 1.1.0 uyumlu
--
-- Kullanım:
--   cqlsh -f schema.cql
--   veya her komutu tek tek cqlsh terminalinde çalıştırın.

-- ============================================================
-- KEYSPACE
-- ============================================================
CREATE KEYSPACE IF NOT EXISTS conquerer
  WITH replication = {
    'class': 'SimpleStrategy',
    'replication_factor': 1
  }
  AND durable_writes = true;

USE conquerer;

-- ============================================================
-- ALL PERSISTENCE IDs (pekko-persistence-query için gerekli)
-- ============================================================
CREATE TABLE IF NOT EXISTS all_persistence_ids (
  persistence_id text PRIMARY KEY
);

-- ============================================================
-- JOURNAL
-- ============================================================
CREATE TABLE IF NOT EXISTS messages (
  persistence_id  text,
  partition_nr    bigint,
  sequence_nr     bigint,
  timestamp       timeuuid,
  timebucket      text,
  writer_uuid     text,
  ser_id          int,
  ser_manifest    text,
  event_manifest  text,
  event           blob,
  meta_ser_id     int,
  meta_ser_manifest text,
  meta            blob,
  tags            set<text>,
  PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH gc_grace_seconds = 864000
  AND compaction = {
    'class': 'SizeTieredCompactionStrategy'
  };

-- pekko-persistence-cassandra events-by-tag için gerekli
CREATE TABLE IF NOT EXISTS tag_views (
  tag_name         text,
  persistence_id   text,
  sequence_nr      bigint,
  timebucket       bigint,
  timestamp        timeuuid,
  tag_pid_sequence_nr bigint,
  event            blob,
  ser_id           int,
  ser_manifest     text,
  event_manifest   text,
  writer_uuid      text,
  meta_ser_id      int,
  meta_ser_manifest text,
  meta             blob,
  PRIMARY KEY ((tag_name, timebucket), timestamp, persistence_id, tag_pid_sequence_nr)
) WITH gc_grace_seconds = 864000
  AND compaction = {
    'class': 'SizeTieredCompactionStrategy'
  };

CREATE TABLE IF NOT EXISTS tag_write_progress (
  persistence_id    text,
  tag               text,
  sequence_nr       bigint,
  tag_pid_sequence_nr bigint,
  offset            timeuuid,
  PRIMARY KEY (persistence_id, tag)
);

CREATE TABLE IF NOT EXISTS tag_scanning (
  persistence_id  text,
  sequence_nr     bigint,
  PRIMARY KEY (persistence_id)
);

CREATE TABLE IF NOT EXISTS metadata (
  persistence_id  text PRIMARY KEY,
  deleted_to      bigint,
  properties      map<text, text>
);

-- ============================================================
-- SNAPSHOT
-- ============================================================
CREATE TABLE IF NOT EXISTS snapshots (
  persistence_id    text,
  sequence_nr       bigint,
  timestamp         bigint,
  ser_id            int,
  ser_manifest      text,
  snapshot_data     blob,
  snapshot          blob,
  meta_ser_id       int,
  meta_ser_manifest text,
  meta              blob,
  PRIMARY KEY (persistence_id, sequence_nr)
) WITH CLUSTERING ORDER BY (sequence_nr DESC)
  AND gc_grace_seconds = 864000
  AND compaction = {
    'class': 'SizeTieredCompactionStrategy'
  };
