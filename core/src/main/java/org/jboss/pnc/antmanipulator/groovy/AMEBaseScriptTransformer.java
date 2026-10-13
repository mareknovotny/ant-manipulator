package org.jboss.pnc.antmanipulator.groovy;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

/**
 * AST transformation behind {@link AMEBaseScript}. Analogous to Groovy's own {@code @BaseScript} (and to
 * pom-manipulation-ext's {@code ASTTransformer}), it:
 * <ol>
 * <li>makes the script class extend {@link BaseScript};</li>
 * <li>binds the annotated variable to the script itself ({@code this}), so {@code ame.getBaseDir()} works;</li>
 * <li>copies {@link InvocationPoint} from the variable declaration onto the script class, where
 * {@link GroovyScriptRunner} reads it reflectively.</li>
 * </ol>
 */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public class AMEBaseScriptTransformer extends AbstractASTTransformation {

    private static final ClassNode BASE_SCRIPT_TYPE = ClassHelper.make(BaseScript.class);
    private static final ClassNode INVOCATION_POINT_TYPE = ClassHelper.make(InvocationPoint.class);

    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {
        init(nodes, source);
        AnnotatedNode parent = (AnnotatedNode) nodes[1];
        AnnotationNode annotation = (AnnotationNode) nodes[0];

        if (!(parent instanceof DeclarationExpression)) {
            addError("@AMEBaseScript is only allowed on a script-level variable declaration.", annotation);
            return;
        }
        DeclarationExpression declaration = (DeclarationExpression) parent;
        if (!(declaration.getRightExpression() instanceof EmptyExpression)) {
            addError("@AMEBaseScript variable must not be initialised; it is bound to the script itself.", declaration);
            return;
        }

        ClassNode scriptClass = null;
        for (ClassNode c : source.getAST().getClasses()) {
            if (c.isScript()) {
                scriptClass = c;
                break;
            }
        }
        if (scriptClass == null) {
            addError("@AMEBaseScript can only be used in a Groovy script.", annotation);
            return;
        }

        scriptClass.setSuperClass(BASE_SCRIPT_TYPE);
        declaration.setRightExpression(new VariableExpression("this"));
        for (AnnotationNode a : declaration.getAnnotations(INVOCATION_POINT_TYPE)) {
            scriptClass.addAnnotation(a);
        }
    }
}
