package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LangFileTest {
	/** Tandem reads its English the way this does, and a key written twice stops it (Minecraft's own reader doesn't mind). */
	@Test
	void theEnglishFileReadsAsTheGameReadsIt() throws IOException {
		try (Reader r = Files.newBufferedReader(Paths.get("../fabric/src/main/resources/assets/tandem/lang/en_us.json"), StandardCharsets.UTF_8)) {
			Map<String, String> words = new Gson().fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
			assertFalse(words.isEmpty());
		}
	}
}
