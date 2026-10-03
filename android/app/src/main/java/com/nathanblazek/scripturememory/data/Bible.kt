package com.nathanblazek.scripturememory.data

data class Book(val name: String, val chapters: Int)

object Bible {
    val books: List<Book> = listOf(
        Book("Genesis", 50), Book("Exodus", 40), Book("Leviticus", 27), Book("Numbers", 36), Book("Deuteronomy", 34),
        Book("Joshua", 24), Book("Judges", 21), Book("Ruth", 4), Book("1 Samuel", 31), Book("2 Samuel", 24),
        Book("1 Kings", 22), Book("2 Kings", 25), Book("1 Chronicles", 29), Book("2 Chronicles", 36), Book("Ezra", 10),
        Book("Nehemiah", 13), Book("Esther", 10), Book("Job", 42), Book("Psalms", 150), Book("Proverbs", 31),
        Book("Ecclesiastes", 12), Book("Song of Solomon", 8), Book("Isaiah", 66), Book("Jeremiah", 52), Book("Lamentations", 5),
        Book("Ezekiel", 48), Book("Daniel", 12), Book("Hosea", 14), Book("Joel", 3), Book("Amos", 9),
        Book("Obadiah", 1), Book("Jonah", 4), Book("Micah", 7), Book("Nahum", 3), Book("Habakkuk", 3),
        Book("Zephaniah", 3), Book("Haggai", 2), Book("Zechariah", 14), Book("Malachi", 4),
        Book("Matthew", 28), Book("Mark", 16), Book("Luke", 24), Book("John", 21), Book("Acts", 28),
        Book("Romans", 16), Book("1 Corinthians", 16), Book("2 Corinthians", 13), Book("Galatians", 6), Book("Ephesians", 6),
        Book("Philippians", 4), Book("Colossians", 4), Book("1 Thessalonians", 5), Book("2 Thessalonians", 3), Book("1 Timothy", 6),
        Book("2 Timothy", 4), Book("Titus", 3), Book("Philemon", 1), Book("Hebrews", 13), Book("James", 5),
        Book("1 Peter", 5), Book("2 Peter", 3), Book("1 John", 5), Book("2 John", 1), Book("3 John", 1),
        Book("Jude", 1), Book("Revelation", 22),
    )
}
