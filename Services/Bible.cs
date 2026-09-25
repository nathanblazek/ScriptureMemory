namespace ScriptureMemory.Services;

public record Book(string Name, int Chapters)
{
    public override string ToString() => Name;
}

public static class Bible
{
    public static IReadOnlyList<Book> Books { get; } = new Book[]
    {
        new("Genesis", 50), new("Exodus", 40), new("Leviticus", 27), new("Numbers", 36), new("Deuteronomy", 34),
        new("Joshua", 24), new("Judges", 21), new("Ruth", 4), new("1 Samuel", 31), new("2 Samuel", 24),
        new("1 Kings", 22), new("2 Kings", 25), new("1 Chronicles", 29), new("2 Chronicles", 36), new("Ezra", 10),
        new("Nehemiah", 13), new("Esther", 10), new("Job", 42), new("Psalms", 150), new("Proverbs", 31),
        new("Ecclesiastes", 12), new("Song of Solomon", 8), new("Isaiah", 66), new("Jeremiah", 52), new("Lamentations", 5),
        new("Ezekiel", 48), new("Daniel", 12), new("Hosea", 14), new("Joel", 3), new("Amos", 9),
        new("Obadiah", 1), new("Jonah", 4), new("Micah", 7), new("Nahum", 3), new("Habakkuk", 3),
        new("Zephaniah", 3), new("Haggai", 2), new("Zechariah", 14), new("Malachi", 4),
        new("Matthew", 28), new("Mark", 16), new("Luke", 24), new("John", 21), new("Acts", 28),
        new("Romans", 16), new("1 Corinthians", 16), new("2 Corinthians", 13), new("Galatians", 6), new("Ephesians", 6),
        new("Philippians", 4), new("Colossians", 4), new("1 Thessalonians", 5), new("2 Thessalonians", 3), new("1 Timothy", 6),
        new("2 Timothy", 4), new("Titus", 3), new("Philemon", 1), new("Hebrews", 13), new("James", 5),
        new("1 Peter", 5), new("2 Peter", 3), new("1 John", 5), new("2 John", 1), new("3 John", 1),
        new("Jude", 1), new("Revelation", 22),
    };
}
