package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 删除已到期执行自己的目录，符号链接不能把清理指向其他执行或目录。
 */
@Component
public class ExecutionFileCleanup {
    private final ExecutionPaths paths;

    public ExecutionFileCleanup(ExecutionPaths paths) {
        this.paths = paths;
    }

    public void remove(RunRecord run) {
        if (!run.terminal()) {
            throw new IllegalArgumentException(Messages.text("error.agent.executionFileCleanup.remove.1"));
        }
        removeTree(paths.workspace(run).getParent());
        removeTree(paths.state(run).getParent());
    }

    private void removeTree(Path target) {
        Path absolute = target.toAbsolutePath().normalize();
        // ExecutionPaths 只用编码后的标识组成路径，固定保留到本次执行目录。
        if (!absolute.equals(target) || absolute.getParent() == null || !"run".equals(
            absolute.getParent().getFileName().toString())) {
            throw new IllegalArgumentException(Messages.text("error.agent.executionFileCleanup.removeTree.1"));
        }
        for (Path part = absolute; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IllegalStateException(Messages.text("error.agent.executionFileCleanup.removeTree.2"));
            }
        }
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failed) {
            throw new UncheckedIOException(Messages.text("error.agent.executionFileCleanup.removeTree.3"), failed);
        }
    }
}
