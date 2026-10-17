package dev.fmangoni.forge.tour.read;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.fmangoni.forge.tour.read.model.ParsedMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Walks one parsed file, keeping the outermost type and every method in source order.
 *
 * <p>Methods of nested types are kept too. Nested types are against the rules in Forge itself, but
 * this tool gets pointed at other people's projects, and silently leaving out half a class would be
 * a tour that is wrong rather than one that is short.
 */
final class Collector extends TreePathScanner<Void, Void> {

    private final CompilationUnitTree unit;
    private final Trees trees;
    private final SourcePositions positions;
    private final List<ParsedMethod> methods = new ArrayList<>();
    private String name;
    private String kind = "class";
    private String doc = "";

    Collector(CompilationUnitTree unit, Trees trees) {
        this.unit = unit;
        this.trees = trees;
        this.positions = trees.getSourcePositions();
    }

    String name() {
        return name;
    }

    String kind() {
        return kind;
    }

    String doc() {
        return doc;
    }

    List<ParsedMethod> methods() {
        return methods;
    }

    @Override
    public Void visitClass(ClassTree type, Void ignored) {
        if (name == null) {
            name = type.getSimpleName().toString();
            kind = type.getKind().toString().toLowerCase(Locale.ROOT).replace("_type", "");
            doc = docHere();
        }
        return super.visitClass(type, ignored);
    }

    @Override
    public Void visitMethod(MethodTree method, Void ignored) {
        methods.add(new ParsedMethod(
                method.getName().toString(),
                parametersOf(method),
                docHere(),
                lineOf(positions.getStartPosition(unit, method)),
                lineOf(positions.getEndPosition(unit, method))));
        return super.visitMethod(method, ignored);
    }

    private String parametersOf(MethodTree method) {
        return method.getParameters().stream()
                .map(VariableTree::getName)
                .map(Object::toString)
                .collect(Collectors.joining(", "));
    }

    private String docHere() {
        String comment = trees.getDocComment(getCurrentPath());
        return comment == null ? "" : comment;
    }

    private int lineOf(long position) {
        return position < 0 ? 0 : (int) unit.getLineMap().getLineNumber(position);
    }
}
