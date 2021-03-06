import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.20"
    `maven-publish`
}

group = "net.lamgc.scext"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()

    maven("https://raw.githubusercontent.com/LamGC/maven-repository/releases/") {
        mavenContent {
            releasesOnly()
        }
    }
}

dependencies {
    implementation("io.github.microutils:kotlin-logging:2.1.21")
    compileOnly("net.lamgc:scalabot-extension:0.2.0")

    val ociSdkVer = "2.24.0"
    implementation("com.oracle.oci.sdk:oci-java-sdk-core:$ociSdkVer")
    implementation("com.oracle.oci.sdk:oci-java-sdk-identity:$ociSdkVer")
    implementation("com.oracle.oci.sdk:oci-java-sdk-objectstorage:$ociSdkVer")

    implementation("org.apache.httpcomponents.client5:httpclient5:5.1.3")

    implementation("org.ktorm:ktorm-core:3.4.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.20")
    implementation("org.xerial:sqlite-jdbc:3.36.0.3")

    implementation("com.google.code.gson:gson:2.9.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

publishing {
    repositories {
        mavenLocal()
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Oracle-manager")
                description.set("在 Telegram 管理你的 Oracle.")

                url.set("https://github.com/LamGC/oracle-manager")
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://www.opensource.org/licenses/mit-license.php")
                    }
                }
                developers {
                    developer {
                        id.set("LamGC")
                        name.set("LamGC")
                        email.set("lam827@lamgc.net")
                        url.set("https://github.com/LamGC")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/LamGC/oracle-manager.git")
                    developerConnection.set("scm:git:https://github.com/LamGC/oracle-manager.git")
                    url.set("https://github.com/LamGC/oracle-manager")
                }
                issueManagement {
                    url.set("https://github.com/LamGC/oracle-manager/issues")
                    system.set("Github Issues")
                }
            }
        }
    }

}