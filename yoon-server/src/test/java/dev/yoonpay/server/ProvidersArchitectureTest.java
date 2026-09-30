package dev.yoonpay.server;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/** Provider modules depend on the core SPI, their support library, Jackson and the JDK (incl. crypto and TLS) — nothing else. */
@AnalyzeClasses(packages = "dev.yoonpay.provider", importOptions = ImportOption.DoNotIncludeTests.class)
class ProvidersArchitectureTest {

    @ArchTest
    static final ArchRule providers_stay_framework_free = classes()
            .that().resideInAPackage("dev.yoonpay.provider..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("dev.yoonpay.provider..", "dev.yoonpay.core..", "tools.jackson..", "java..", "javax.crypto..", "javax.net.ssl..");

    @ArchTest
    static final ArchRule providers_do_not_know_each_other = classes()
            .that().resideInAPackage("dev.yoonpay.provider.paydunya..")
            .should().onlyDependOnClassesThat().resideOutsideOfPackages("dev.yoonpay.provider.dexpay..", "dev.yoonpay.provider.naboopay..", "dev.yoonpay.provider.pispi..");
}
