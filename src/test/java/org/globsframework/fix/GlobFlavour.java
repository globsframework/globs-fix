package org.globsframework.fix;

import org.globsframework.core.model.GlobFactoryService;

import java.util.function.Supplier;

/**
 * The three {@link org.globsframework.core.model.GlobFactory} implementations worth comparing : core's
 * DefaultGlob, and the two ASM flavours of globs-generate.
 * <p>
 * The factory is chosen once, when the {@link org.globsframework.core.metamodel.GlobType} is built
 * (DefaultGlobType's constructor asks {@code GlobFactoryService.Builder.getBuilderFactory()}), so switching the
 * {@code globs.builder} property around the type declaration is enough to get several flavours living in the
 * same JVM.
 */
public enum GlobFlavour {
    DEFAULT(null),
    OBJECT("org.globsframework.model.generator.object.GeneratorGlobFactoryService"),
    PRIMITIVE("org.globsframework.model.generator.primitive.GeneratorGlobFactoryService");

    public final String factoryService;

    GlobFlavour(String factoryService) {
        this.factoryService = factoryService;
    }

    /** Runs {@code toRun} with this flavour installed, and restores the previous setting afterwards. */
    public <T> T build(Supplier<T> toRun) {
        String previous = System.getProperty("globs.builder");
        install(factoryService);
        try {
            return toRun.get();
        } finally {
            install(previous);
        }
    }

    private static void install(String factoryService) {
        if (factoryService == null) {
            System.clearProperty("globs.builder");
        } else {
            System.setProperty("globs.builder", factoryService);
        }
        GlobFactoryService.Builder.reset();
    }
}
