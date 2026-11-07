package xyz.apollodorus.mcgf.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** BookScreen displays at most fourteen 114-pixel lines per page. Leave room around the entries. */
public final class JournalBook {
    private static final int PAGE_LINES = 13;
    private JournalBook() {}

    public static void open(MinecraftClient client, String journal) {
        if (client.player == null) return;
        List<Text> pages = new ArrayList<>();
        List<String> page = new ArrayList<>();
        for (String paragraph : journal.split("\n")) {
            var lines = client.textRenderer.getTextHandler().wrapLines(paragraph, 110, Style.EMPTY);
            if (!page.isEmpty()) {
                if (page.size() + 1 + lines.size() > PAGE_LINES) finishPage(pages, page);
                else page.add("");
            }
            for (StringVisitable line : lines) {
                if (page.size() == PAGE_LINES) finishPage(pages, page);
                page.add(line.getString());
            }
        }
        if (!page.isEmpty()) finishPage(pages, page);
        client.setScreen(new BookScreen(new BookScreen.Contents() {
            @Override
            public int getPageCount() {
                return pages.size();
            }

            @Override
            public StringVisitable getPageUnchecked(int index) {
                return pages.get(index);
            }
        }));
    }

    private static void finishPage(List<Text> pages, List<String> page) {
        pages.add(Text.literal(String.join("\n", page)));
        page.clear();
    }
}
