package org.jboss.pnc.antmanipulator.groovy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.codehaus.groovy.transform.GroovyASTTransformationClass;

/**
 * Groovy base-script annotation for Ant Manipulation scripts.
 *
 * <p>
 * Apply this to the script-level variable that holds the injected context object, together with
 * {@link InvocationPoint}, to declare a well-formed AME Groovy script:
 *
 * <pre>
 * &#64;InvocationPoint(invocationPoint = InvocationStage.FIRST)
 * &#64;AMEBaseScript BaseScript ame
 *
 * ame.getLogger().info("Running in {}", ame.getBaseDir())
 * </pre>
 *
 * <p>
 * The AST transformation ({@code AMEBaseScriptTransformer}) wires the annotated variable to
 * {@link BaseScript} so that Groovy's script infrastructure treats the script class as a subclass of
 * {@code BaseScript}, making all its API methods directly available. This mirrors the approach used by
 * pom-manipulation-ext's {@code @PMEBaseScript} annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.LOCAL_VARIABLE })
@GroovyASTTransformationClass("org.jboss.pnc.antmanipulator.groovy.AMEBaseScriptTransformer")
public @interface AMEBaseScript {
}
