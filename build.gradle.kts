plugins { java }

group = "com.mobopt"
version = (findProperty("pluginVersion") as String?) ?: "1.1.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // O "+" também casa com versões -beta (ex.: 26.3.build.143-beta)
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
