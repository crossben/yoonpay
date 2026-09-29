package dev.yoonpay.core;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@AnalyzeClasses(packages = "dev.yoonpay.core", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTest {

    /** Core depends on the JDK and itself, nothing else. */
    @ArchTest
    static final ArchRule core_depends_on_nothing = classes()
            .that().resideInAPackage("dev.yoonpay.core..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("dev.yoonpay.core..", "java..");
}
