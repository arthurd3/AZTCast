package com.azt.streaming;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fitness functions for the vertical-slice structure.
 *
 * <p>Every rule here encodes a defect this codebase actually had. They are not style preferences —
 * each one is a regression guard with a name, and the comment on it says what it prevents from
 * coming back.
 */
class ArchitectureTest {

    private static final String ROOT = "com.azt.streaming";

    private final JavaClasses classes =
            new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

    @Test
    @DisplayName("feature slices are free of cycles")
    void featureSlicesAreAcyclic() {
        // Scoped to the four pipeline slices on purpose. shared/ is the kernel and is excluded
        // because shared.error is the API's error boundary: a single @RestControllerAdvice has to
        // name every slice's exceptions, so it points at all of them by design. That is safe only
        // because nothing points back at it — which nothingDependsOnTheErrorBoundary() proves.
        SlicesRuleDefinition.slices()
                .matching(ROOT + ".(acquisition|transcoding|playback|ingestion)..")
                .should()
                .beFreeOfCycles()
                .check(classes);
    }

    @Test
    @DisplayName("nothing depends on the error boundary")
    void nothingDependsOnTheErrorBoundary() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".shared.error..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".shared.error..")
                .check(classes);
    }

    @Test
    @DisplayName("web DTOs stay inside their slice — the layer inversion that started this")
    void nothingOutsideIngestionDependsOnItsWebDtos() {
        // MagnetStreamingOrchestrator imported controller.request.MagnetUrl, making the service
        // layer depend on the web layer.
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".ingestion..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".ingestion.web..")
                .check(classes);
    }

    @Test
    @DisplayName("pipeline stages do not know about each other")
    void acquisitionAndTranscodingAreIndependent() {
        // They compose through ingestion. Coupling them directly would make either impossible to
        // replace without touching the other.
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".acquisition..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".transcoding..")
                .check(classes);

        noClasses()
                .that()
                .resideInAPackage(ROOT + ".transcoding..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".acquisition..")
                .check(classes);
    }

    @Test
    @DisplayName("the media-root owner depends on no feature slice")
    void storageStaysIndependent() {
        // FileSystemMediaStorage enforces path containment. Keeping it free of slice dependencies
        // is what lets every slice route through it instead of resolving paths itself.
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".shared.storage..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".acquisition..", ROOT + ".transcoding..", ROOT + ".playback..", ROOT + ".ingestion..")
                .check(classes);
    }

    @Test
    @DisplayName("pipeline stages carry no web types")
    void acquisitionAndTranscodingAvoidWebTypes() {
        // IStreamingService.getVideoPlaylist() returned a Spring Resource, dragging the web stack
        // into a contract about encoding video.
        noClasses()
                .that()
                .resideInAnyPackage(ROOT + ".acquisition..", ROOT + ".transcoding..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.web..", "org.springframework.core.io..")
                .check(classes);
    }

    @Test
    @DisplayName("no AWT on a headless server")
    void nothingDependsOnAwt() {
        // VideoInfo had a java.awt.Image field, which Jackson cannot serialise and a headless JVM
        // should never load.
        noClasses().should().dependOnClassesThat().resideInAPackage("java.awt..").check(classes);
    }

    @Test
    @DisplayName("logging goes through SLF4J, never the standard streams")
    void nothingWritesToStandardStreams() {
        // StreamingServiceImpl printed to System.out five times, including every line of ffmpeg
        // output, despite having a logger.
        NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
    }

    @Test
    @DisplayName("controllers live in their slice's web package")
    void controllersStayInWebPackages() {
        classes()
                .that()
                .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should()
                .resideInAnyPackage(ROOT + ".playback.web..", ROOT + ".ingestion.web..")
                .check(classes);
    }

    @Test
    @DisplayName("no C#-style I-prefixed interface names")
    void interfacesAreNotIPrefixed() {
        // IStreamingService and ITorrentService. The prefix is a C# convention; Java names the
        // capability instead.
        noClasses()
                .that()
                .areInterfaces()
                .should()
                // haveNameMatching works on the fully-qualified name, hence the package prefix.
                .haveNameMatching(".*\\.I[A-Z][A-Za-z0-9]*")
                .check(classes);
    }

    @Test
    @DisplayName("shared knows nothing about the slices that use it")
    void sharedConfigStaysAtTheBottom() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".shared.config..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".acquisition..", ROOT + ".transcoding..", ROOT + ".playback..", ROOT + ".ingestion..")
                .check(classes);
    }
}
