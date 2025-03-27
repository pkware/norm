plugins {
  `kotlin-dsl`
  `java-gradle-plugin`
  `kotlin-only`
  id("com.pkware.gradle.publish")
}

gradlePlugin {
  plugins {
    register("norm-plugin") {
      id = "com.pkware.norm"
      implementationClass = "com.pkware.norm.gradle.NormPlugin"
    }
  }
}
