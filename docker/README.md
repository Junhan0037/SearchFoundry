# CloudGate IAM Local Infra

로컬 개발을 위한 MySQL, Redis, Kafka(KRaft) 환경을 `docker-compose.yml`로 제공합니다.

## 사전 준비
- Docker Desktop 혹은 Docker Engine + Docker Compose v2
- 4GB 이상의 여유 메모리(세 서비스 동시 구동 시 권장)

## 실행 방법
1. `cd docker`
2. `docker compose up -d` 또는 `docker compose -f docker-compose.yml up -d`
   - 첫 실행 시 이미지 다운로드가 필요하므로 네트워크 연결이 요구됩니다.
3. 중지: `docker compose down`
4. 데이터까지 정리: `docker compose down -v`
