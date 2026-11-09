# Tandem

Play Minecraft together without renting a server, and keep a world synced so anyone in the group can host it.

Prototype: Fabric 1.21.1 only. Ports to the other loaders and versions come once this feels complete.

| Folder | What it is |
| --- | --- |
| `core/` | Plain Java 8, no Minecraft: world manifests, diffs, the crash-safe world swap. Shared by every port |
| `fabric/` | The Fabric 1.21.1 mod: the title screen button and the Tandem screen |

```
./gradlew :core:test        tests for core
./gradlew build             the mod jar, in fabric/build/libs
./gradlew :fabric:runClient Minecraft with the mod, for trying it out
```

Builds need JDK 21. Loom is pinned to 1.17 because 1.18 and later need Java 25 to run Gradle.

## License

The code is licensed under [GPL-3.0](LICENSE).

The Tandem and KeplerSync names and logos (`fabric/src/main/resources/assets/tandem/icon.png` and the logo in `fabric/src/main/resources/assets/tandem/textures/icon/logo.png`) aren't covered by that license and may not be used without permission.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
