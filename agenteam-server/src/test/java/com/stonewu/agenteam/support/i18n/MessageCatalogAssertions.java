package com.stonewu.agenteam.support.i18n;

import com.stonewu.agenteam.service.i18n.Messages;
import com.stonewu.agenteam.model.i18n.entity.UiLanguage;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

/** 检查生产源码中的提示、日志与约束引用；注释和已冻结的迁移文件名保持原样。 */
public final class MessageCatalogAssertions {
    private static final Pattern CHINESE = Pattern.compile("[\\p{IsHan}]");

    public static void verify(Path... roots) throws Exception {
        var files = new ArrayList<Path>();
        for (Path directory : roots) {
            if (Files.isDirectory(directory)) {
                try (var entries = Files.walk(directory)) {
                    entries.filter(file -> file.toString().endsWith(".java")).forEach(files::add);
                }
            }
        }
        assertFalse(files.isEmpty());
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var problems = new ArrayList<String>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(files));
            var trees = Trees.instance(task);
            for (var unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        if (List.of("Messages.text", "Messages.source", "Messages.log", "Messages.in").contains(node.getMethodSelect().toString())) {
                            int index = node.getMethodSelect().toString().equals("Messages.in") ? 1 : 0;
                            if (node.getArguments().size() > index && node.getArguments().get(index) instanceof LiteralTree key
                                && key.getValue() instanceof String value) {
                                if (!Messages.contains(value)) {
                                    report(node, "消息代码不存在：" + value);
                                } else if (node.getMethodSelect().toString().equals("Messages.log")) {
                                    String english = Messages.in(UiLanguage.ENGLISH, value);
                                    String original = Messages.source(value);
                                    if (CHINESE.matcher(english).find()
                                        || english.split("\\{\\}", -1).length != original.split("\\{\\}", -1).length) {
                                        report(node, "日志应使用英文并保留全部日志参数：" + value);
                                    }
                                }
                            }
                        }
                        return super.visitMethodInvocation(node, unused);
                    }

                    @Override
                    public Void visitLiteral(LiteralTree node, Void unused) {
                        if (!(node.getValue() instanceof String value)) {
                            return super.visitLiteral(node, unused);
                        }
                        boolean annotation = false, constraint = false;
                        for (TreePath path = getCurrentPath().getParentPath(); path != null; path = path.getParentPath()) {
                            Tree parent = path.getLeaf();
                            annotation |= parent instanceof AnnotationTree;
                            constraint |= parent instanceof AssignmentTree assignment
                                && assignment.getVariable().toString().equals("message");
                            if (parent instanceof MethodTree) {
                                break;
                            }
                        }
                        boolean migrationFile = unit.getSourceFile().getName().endsWith("ProDatabaseMigration.java")
                            && List.of("V0.2.0.2__新增离线许可证及校验状态.sql", "V0.2.0.3__补充社区升级后的内置商业权限.sql").contains(value);
                        if (CHINESE.matcher(value).find() && !migrationFile) {
                            report(node, "生产文案应放入消息目录，固定协议值使用原文入口");
                        }
                        if (annotation && constraint && value.matches("\\{(?:pro\\.)?(?:error|validation|text)\\..*}")) {
                            if (!Messages.contains(value.substring(1, value.length() - 1))) {
                                report(node, "约束引用的消息代码不存在：" + value);
                            }
                        }
                        return super.visitLiteral(node, unused);
                    }

                    private void report(Tree node, String message) {
                        long start = trees.getSourcePositions().getStartPosition(unit, node);
                        problems.add(unit.getSourceFile().getName() + ":" + unit.getLineMap().getLineNumber(start)
                            + " " + message);
                    }
                }.scan(unit, null);
            }
        }
        assertTrue(diagnostics.getDiagnostics().stream().noneMatch(item -> item.getKind().name().equals("ERROR")),
            diagnostics.getDiagnostics().toString());
        assertEquals(List.of(), problems);
    }
}
