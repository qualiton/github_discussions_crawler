lazy val root =
  (project in file("."))
    .aggregate(server, crawler, slackapiclient, database, common)
    .dependsOn(server, crawler, slackapiclient, database, common)
    .settings(BaseSettings.default)
    .withDependencies
    .withDocker
    .withRelease
    .withAlpn
    .withAspectJ

lazy val common =
  (project in file("common"))
    .withTestConfig(64)
    .withDependencies

lazy val slackapiclient =
  (project in file("slackapiclient"))
    .withTestConfig(40.22)
    .withDependencies

lazy val crawler =
  (project in file("crawler"))
    .dependsOn(database % "test->test;compile->compile", common % "test->test;compile->compile", slackapiclient % "test->test;compile->compile")
    .withTestConfig(82.2)
    .withDependencies

lazy val server =
  (project in file("server"))
    .dependsOn(crawler % "test->test;compile->compile")
    .settings(Seq(sources in (Compile, doc) := Seq.empty)) // REASON: No documentation generated with unsuccessful compiler run
    .withTestConfig(16.4)
    .withDependencies
    .withLocalAlpn

lazy val database =
  (project in file("database"))
    .dependsOn(common)
    .withDependencies

