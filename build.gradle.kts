plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.ohmytoken"
version = "0.5.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 编译目标：IntelliJ IDEA Community 2024.3
        // 想验证更新的 IDE，改这里的版本号即可
        create("IC", "2024.3")
    }
}

// 取 CHANGELOG.md 里最新一节的条目，转成 HTML 列表
val latestChangeNotes: String = run {
    val items = file("CHANGELOG.md").readLines()
        .dropWhile { !it.startsWith("## [") }
        .drop(1)
        .takeWhile { !it.startsWith("## [") }
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .joinToString("") { "<li>${it.removePrefix("- ")}</li>" }
    "<ul>$items</ul>"
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }
        changeNotes.set(latestChangeNotes)
    }

    // 发布：在 Marketplace 后台生成 token 后，
    // PUBLISH_TOKEN=xxx ./gradlew publishPlugin
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // 兼容性验证：./gradlew runPluginVerifier
    // 会下载多个版本的 IDE（几个 GB），发布前跑一次即可
    pluginVerification {
        ides {
            recommended()
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10.2"
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
    patchPluginXml {
        // 置空 until-build：provider 返回 null 即"无值"，属性不会写进 plugin.xml
        untilBuild.set(providers.provider<String> { null })
    }
}
