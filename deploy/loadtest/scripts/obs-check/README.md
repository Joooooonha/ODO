# DB 관찰 지표 검증 (sql_exporter ①②③)

- `classify.sql` — 코드에서 잠금을 잡는 문장 전부를 쿼리 ②의 원문 CASE·수정안 CASE에 넣어 본다(측정 DB에서 `psql -f`)
- `hold.sh` — postgres 컨테이너 안에서 잠금 경합을 만든다. A: BEGIN 후 행을 잠그고 커밋하지 않은 채 머무름(idle in transaction),
  B: 같은 행을 바꾸려다 기다림(Lock). 둘 다 ROLLBACK
  ```
  docker compose -f deploy/loadtest/compose.yml cp deploy/loadtest/scripts/obs-check/hold.sh postgres:/tmp/hold.sh
  docker compose -f deploy/loadtest/compose.yml exec -d -T postgres sh /tmp/hold.sh "<A 문장>" "<B 문장>" 40 3
  docker compose -f deploy/loadtest/compose.yml exec -T recorder wget -qO- http://sql-exporter:9399/metrics | grep ^pg_obs
  ```
- 기대: ② `target_table=<표>, lock_kind=transactionid` 1, ③ 초 단위로 증가, ① `idle in transaction`(wait_type=Client) +1·`active/Lock` +1.
  대기 0이면 ③은 0으로 남고, ②는 시계열이 사라진다(Grafana에서 `or vector(0)`로 0을 채운다), ①은 지금 있는 조합만 나온다
