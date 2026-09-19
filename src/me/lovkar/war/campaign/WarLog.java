package me.lovkar.war.campaign;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * A war has a history you can read afterwards.
 *
 * <p>One line per thing that happened - who marched, with how many, what became of them, what
 * they carried home - kept by day, for both colonies. The Town Hall keeps MineColonies' raid
 * history; this is the same idea for wars, and it is what {@code /war log} reads.</p>
 */
public final class WarLog {
    private static final String TAG_LOG = "log";
    /** Lines kept per world; the oldest go first. */
    private static final int KEEP = 200;

    public record Entry(long day, int a, int b, String text) {
        public boolean involves(final int colony) {
            return a == colony || b == colony;
        }

        public String line() {
            return "day " + day + ": " + text;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(final Level level, final int a, final int b, final String text) {
        final long day = level == null ? 0 : level.getGameTime() / 24000L;
        entries.add(new Entry(day, a, b, text));
        while (entries.size() > KEEP) {
            entries.remove(0);
        }
    }

    /** The last {@code count} lines about this colony, oldest first. */
    public List<Entry> about(final int colony, final int count) {
        final List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < count; i--) {
            if (entries.get(i).involves(colony)) {
                out.add(0, entries.get(i));
            }
        }
        return out;
    }

    public int size() {
        return entries.size();
    }

    void read(final CompoundTag tag) {
        entries.clear();
        for (final Tag t : tag.getList(TAG_LOG, Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) t;
            entries.add(new Entry(one.getLong("day"), one.getInt("a"), one.getInt("b"), one.getString("text")));
        }
    }

    void write(final CompoundTag tag) {
        final ListTag list = new ListTag();
        for (final Entry e : entries) {
            final CompoundTag one = new CompoundTag();
            one.putLong("day", e.day());
            one.putInt("a", e.a());
            one.putInt("b", e.b());
            one.putString("text", e.text());
            list.add(one);
        }
        tag.put(TAG_LOG, list);
    }
}
