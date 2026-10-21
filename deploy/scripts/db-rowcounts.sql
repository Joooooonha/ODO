-- DB 이전 대조용: 테이블별 행 수, Flyway 마지막 버전, 큰 객체 수. 옛 DB와 새 DB에서 각각 돌려 diff한다
--   sudo -u postgres psql -d dance_db -XAt -f /tmp/db-rowcounts.sql > /tmp/rowcounts-<old|new>.txt
SELECT format('SELECT %L || '' '' || count(*) FROM %I.%I;', table_schema || '.' || table_name, table_schema, table_name)
  FROM information_schema.tables
 WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
 ORDER BY table_name \gexec
SELECT 'flyway_max ' || max(version::int) FROM flyway_schema_history WHERE success;
SELECT 'large_objects ' || count(*) FROM pg_largeobject_metadata;
