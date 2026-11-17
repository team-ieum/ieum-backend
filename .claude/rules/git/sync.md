# 브랜치·동기화
- `main`(main 배포) ← `dev`(dev 배포) ← 작업 브랜치. 작업 브랜치는 `dev`에서 딴다
- 작업 브랜치 이름 `{feat|fix|refactor|chore|infra}/IEUM-BE-{n}` (Notion 이슈 ID). 수명 최대 3일
- pull은 `git pull --rebase`
- `main`·`dev` 직접 push·force push 금지. 작업 브랜치는 `--force-with-lease`만
- `catalog` 서브모듈은 ieum-node-catalog에 머지된 커밋만 가리킨다. 포인터 올림은 따로 `chore` PR
