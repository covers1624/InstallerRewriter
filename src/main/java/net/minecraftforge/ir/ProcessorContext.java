package net.minecraftforge.ir;

import net.covers1624.quack.maven.MavenNotation;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Created by covers1624 on 22/5/21.
 */
public abstract class ProcessorContext {

    public final MavenNotation notation;
    public final MavenNotation installer;
    public final Path repoPath;

    public ProcessorContext(MavenNotation notation, MavenNotation installer, Path repoPath) {
        this.notation = notation;
        this.installer = installer;
        this.repoPath = repoPath;
    }

    /**
     * Gets a pair of files to process.
     *
     * @param notation The {@link MavenNotation} describing the file.
     * @return A Pair of paths. Old -> New
     */
    public abstract Pair<Path, Path> getFile(MavenNotation notation) throws IOException;

}
