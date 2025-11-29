# Helm 차트 설치

## 준비

ROOT/values.tpl.yaml 파일 내용을 복사하여 ROOT/values.local.yaml 파일을 만들고,
values.local.yaml 파일에 값을 채워넣습니다.

## 실행

다음 명령을 실행하여 클러스터에 auth-gateway 애플리케이션을 배포합니다.

```bash
helm upgrade --install auth-gateway ./helm/auth-gateway -f values.local.yaml -n goormdotcom-local
# -n: 애플리케이션이 속할 네임스페이스
# -f: apply 시점에 주입될 변수들을 모아놓은 파일
```
