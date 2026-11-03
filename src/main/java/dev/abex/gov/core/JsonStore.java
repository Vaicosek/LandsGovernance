package dev.abex.gov.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** One JSON file; written to a temp file first so a crash mid-save can't truncate it. */
public final class JsonStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public JsonStore(Path file) {
        this.file = file;
    }

    public GovData load() throws IOException {
        if (!Files.exists(file)) return new GovData();
        GovData d = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), GovData.class);
        return d == null ? new GovData() : d;
    }

    public void save(GovData data) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
