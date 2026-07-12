# clustered-wiremock documentation

Organised by the [Diátaxis](https://diataxis.fr) framework (the "grand unified theory of
documentation") — four kinds of docs serving four different needs:

| Doc | Kind | Read it when you want to… |
|-----|------|--------------------------|
| **[Tutorial](tutorial.md)** | Learning-oriented | Stand up a two-node shared-journal cluster from nothing and see it work |
| **[How-to](how-to.md)** | Task-oriented | Wire the extension into your setup — embedded, standalone, Docker, your own Hazelcast |
| **[Reference](reference.md)** | Information-oriented | Look up the architecture: classes, the WireMock store seam, config keys, API surface |
| **[Explanation](explanation.md)** | Understanding-oriented | Understand **how Hazelcast is used correctly** and why each choice was made |

New here? Start with the [Tutorial](tutorial.md). Already know WireMock and just need to plug it in?
Go to the [How-to](how-to.md). Curious how the distributed bits actually work — the ordering,
serialization, atomicity, and consistency tradeoffs? The [Explanation](explanation.md) is the main
event.
