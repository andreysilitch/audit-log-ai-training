package com.example.audit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.example.audit",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule layeredBoundaries =
      layeredArchitecture()
          .consideringAllDependencies()
          .layer("API")
          .definedBy("..audit.api..")
          .layer("Domain")
          .definedBy("..audit.domain..")
          .layer("Persistence")
          .definedBy("..audit.persistence..")
          .layer("Retention")
          .definedBy("..audit.retention..")
          .layer("Tamper")
          .definedBy("..audit.tamper..")

          // API drives HTTP — nothing inside the system should depend on it.
          .whereLayer("API")
          .mayNotBeAccessedByAnyLayer()
          // Persistence is wired via the domain interface; no other code may
          // reach into the persistence package directly.
          .whereLayer("Persistence")
          .mayNotBeAccessedByAnyLayer()
          // Retention is invoked only by Spring scheduler.
          .whereLayer("Retention")
          .mayNotBeAccessedByAnyLayer()
          // Tamper services are an implementation detail of the write path.
          .whereLayer("Tamper")
          .mayOnlyBeAccessedByLayers("Persistence")
          // Domain types are shared by every other layer.
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers("API", "Persistence", "Retention", "Tamper");

  @ArchTest
  static final ArchRule domainStaysFreeOfWebAndPersistence =
      noClasses()
          .that()
          .resideInAPackage("..audit.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.web..",
              "org.springframework.http..",
              "org.springframework.jdbc..",
              "org.springframework.transaction..",
              "jakarta.servlet..",
              "jakarta.persistence..",
              "javax.sql..",
              "java.sql..",
              "..audit.api..",
              "..audit.persistence..",
              "..audit.retention..",
              "..audit.tamper..")
          .because("domain logic must not couple to Spring MVC, JDBC, or sibling layers");

  @ArchTest
  static final ArchRule apiOnlyTalksToDomain =
      noClasses()
          .that()
          .resideInAPackage("..audit.api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..audit.persistence..", "..audit.retention..", "..audit.tamper..")
          .because("controllers are forbidden to reach past the domain service layer");

  @ArchTest
  static final ArchRule persistenceDoesNotDependOnApiOrRetention =
      noClasses()
          .that()
          .resideInAPackage("..audit.persistence..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..audit.api..", "..audit.retention..");

  /**
   * AGENTS.md invariant 1 / architectural rule 7: the audit-event repository exposes
   * append/find/search/latest only — never update/delete/remove.
   */
  @ArchTest
  static final ArchRule auditRepositoryIsAppendOnly =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .haveSimpleName("AuditEventRepository")
          .should()
          .haveNameNotContaining("update")
          .andShould()
          .haveNameNotContaining("delete")
          .andShould()
          .haveNameNotContaining("remove")
          .andShould()
          .haveNameNotContaining("modify");

  /** Spring controllers belong only in the api package. */
  @ArchTest
  static final ArchRule controllersLiveInApiPackage =
      classes()
          .that()
          .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
          .or()
          .areAnnotatedWith("org.springframework.stereotype.Controller")
          .should()
          .resideInAPackage("..audit.api..");

  /** Repository implementations belong only in the persistence package. */
  @ArchTest
  static final ArchRule repositoriesLiveInPersistence =
      classes()
          .that()
          .areAnnotatedWith("org.springframework.stereotype.Repository")
          .should()
          .resideInAPackage("..audit.persistence..");

  /**
   * Audit event POST endpoints must not accept a server-managed timestamp field from the client
   * (AGENTS.md rule 2). Keep this loose: forbid any "timestamp" field on AuditEventRequest
   * specifically.
   */
  @ArchTest
  static final ArchRule requestDtoDoesNotExposeTimestamp =
      noClasses()
          .that()
          .haveSimpleName("AuditEventRequest")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.time.Instant")
          .because("client-supplied timestamp is forbidden — server sets it");
}
