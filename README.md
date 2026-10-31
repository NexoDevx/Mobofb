# MobOptimizer

Plugin de otimização para **Paper 26.3** (Java 25). Funciona também em forks do Paper (Purpur, Pufferfish). Não suporta Folia.

- **Dormir mobs ociosos** (`setAware(false)`) e acordar quando o player chega perto; mais agressivo quando o TPS cai.
- **Agrupar pathfinding**: um líder calcula a rota, os seguidores fazem só caminhos curtos.
- **Limpeza**: itens no chão (por idade e por chunk) e limite de mobs por chunk.
- **Guarda de memória**: monitora a RAM e, em nível crítico, limpa entidades e descarrega chunks parados.

## Build
Local: `gradle build` (Gradle 9.1+, JDK 25). O jar sai em `build/libs/`.
GitHub: dê push; a Action gera o jar em *Actions → Build → Artifacts*.
Para publicar um release: `git tag v1.1.0 && git push --tags`.

## Comandos (`mobopt.admin`)
`/mobopt status` · `/mobopt mem` · `/mobopt clear` · `/mobopt reload`
