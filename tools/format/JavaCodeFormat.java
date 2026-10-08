import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.printer.configuration.Indentation;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 按语法结构补齐控制语句的大括号，统一 Java 排版并核对语法结构不变。 */
public final class JavaCodeFormat {
    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setCharacterEncoding(StandardCharsets.UTF_8));
    private static final PrettyPrinterConfiguration FORMAT = new PrettyPrinterConfiguration()
            .setIndentation(new Indentation(Indentation.IndentType.SPACES, 4))
            .setEndOfLineCharacter("\n")
            .setOrderImports(true);

    private JavaCodeFormat() {
    }

    public static void main(String[] args) throws Exception {
        boolean write = args[0].equals("--write");
        if (!write && !args[0].equals("--check")) {
            throw new IllegalArgumentException("请使用 --write 或 --check");
        }
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        List<String> files = Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8);
        int changes = 0;
        for (String filename : files) {
            if (filename.isBlank()) {
                continue;
            }
            Path path = root.resolve(filename).normalize();
            if (!path.startsWith(root) || !filename.endsWith(".java")) {
                throw new IllegalArgumentException("格式化文件超出代码仓库或不是 Java 源码");
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            CompilationUnit unit = parse(source, filename);
            explicitBlocks(unit);
            String result = unit.toString(FORMAT);
            CompilationUnit verified = parse(result, filename);
            if (!withoutComments(unit).equals(withoutComments(verified))) {
                Files.writeString(root.resolve("target/format-tools/structure-before.txt"), withoutComments(unit).toString(), StandardCharsets.UTF_8);
                Files.writeString(root.resolve("target/format-tools/structure-after.txt"), withoutComments(verified).toString(), StandardCharsets.UTF_8);
                throw new IllegalStateException("格式化改变了语法结构：" + filename);
            }
            if (!source.equals(result)) {
                changes++;
                if (write) {
                    Files.writeString(path, result, StandardCharsets.UTF_8);
                } else {
                    System.out.println("需要格式化：" + filename);
                }
            }
        }
        System.out.println("Java 文件数：" + files.size() + "，需要调整的文件数：" + changes);
        if (!write && changes > 0) {
            System.exit(1);
        }
    }

    private static CompilationUnit parse(String source, String name) {
        var result = PARSER.parse(source);
        if (!result.isSuccessful()) {
            throw new IllegalArgumentException("无法解析 Java 文件：" + name + " " + result.getProblems());
        }
        return result.getResult().orElseThrow();
    }

    private static Statement block(Statement statement) {
        return statement.isBlockStmt() ? statement : new BlockStmt(new NodeList<>(statement.clone()));
    }

    private static void explicitBlocks(CompilationUnit unit) {
        unit.findAll(IfStmt.class).forEach(statement -> {
            statement.setThenStmt(block(statement.getThenStmt()));
            statement.getElseStmt().filter(value -> !value.isIfStmt())
                    .ifPresent(value -> statement.setElseStmt(block(value)));
        });
        unit.findAll(ForStmt.class).forEach(statement -> statement.setBody(block(statement.getBody())));
        unit.findAll(ForEachStmt.class).forEach(statement -> statement.setBody(block(statement.getBody())));
        unit.findAll(WhileStmt.class).forEach(statement -> statement.setBody(block(statement.getBody())));
        unit.findAll(DoStmt.class).forEach(statement -> statement.setBody(block(statement.getBody())));
    }

    private static CompilationUnit withoutComments(CompilationUnit original) {
        var copy = original.clone();
        copy.walk(node -> {
            node.removeComment();
            List.copyOf(node.getOrphanComments()).forEach(node::removeOrphanComment);
        });
        copy.setImports(new NodeList<>());
        return copy;
    }
}
