package com.nilsson.lmo;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.*;

/**
 * <p>The {@code ArchitectureTest} suite enforces structural integrity and architectural
 * constraints across the Latent Model Organizer backend. It ensures adherence to
 * clean architecture principles and prevents structural erosion as the system evolves.</p>
 *
 * <p>The test suite validates:
 * <ul>
 *   <li><b>Package Resilience:</b> Ensures service-layer components are correctly located.</li>
 *   <li><b>Dependency Direction:</b> Prevents circular dependencies and illegal package access.</li>
 *   <li><b>General Coding Rules:</b> Enforces standard practices such as avoiding generic exceptions,
 *   prohibiting field injection, and standardizing logging frameworks.</li>
 * </ul>
 * </p>
 */
@AnalyzeClasses(packages = "com.nilsson.lmo", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule services_should_be_in_service_package =
            classes().that().haveSimpleNameEndingWith("Service")
                    .should().resideInAPackage("..service..");

    @ArchTest
    static final ArchRule domain_should_not_depend_on_service =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..service..");

    @ArchTest
    static final ArchRule no_generic_exceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule no_field_injection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
}