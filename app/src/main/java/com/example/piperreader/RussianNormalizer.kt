package com.example.piperreader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

class RussianNormalizer(private val context: Context) {

    private val yoMap = HashMap<String, String>(80000)
    private var isLoaded = false

    private val compoundPrefixes = mapOf(
        "зелено" to "зелёно",
        "черно" to "чёрно",
        "темно" to "тёмно",
        "пестро" to "пёстро",
        "светло" to "све\u0301тло"
    )

    private val adverbFixes = mapOf(
        "по-моему" to "помоему",
        "по-твоему" to "потвоему",
        "по-своему" to "посвоему"
    )

    private val properNounsMap: Map<String, String> = buildMap {
        val plusRegex = Regex("\\+([аеёиоуыэюя])", RegexOption.IGNORE_CASE)
        val properNounsRaw = listOf(
            "М+аша", "М+аши", "М+аше", "М+ашу", "М+аня", "М+аню", "К+оля", "К+оли", "К+олю",
            "В+али", "В+алю", "П+оля", "П+олю", "П+олей", "П+аши", "П+ашу", "М+ила", "М+илы",
            "Ж+ене", "Ж+еню", "Л+ен", "Дал+и", "С+омов", "С+о", "Варв+ара", "Варв+ары",
            "Варв+аре", "В+иктора", "В+олков", "П+утину", "П+утина", "П+утине", "Мал+ой",
            "З+убов", "Р+огов", "Б+ыков", "Н+осов", "К+отов", "Б+ыков", "Ж+уков", "Макс+иму",
            "Макс+има", "Иван+ов", "Н+осовых", "Толст+ой", "Толст+ого", "Толст+ому", "Толст+ым",
            "Толст+ом", "Толст+ая", "Толст+ую", "Т+ома", "Т+ому", "Сокол+ов", "Тигр+ан",
            "С+олкинд", "С+олкинда", "С+ахоров", "Г+ора", "Л+уна", "Л+уны", "Лук+а", "Лук+и",
            "Дамил+олы", "Дамил+олу", "Земл+я", "Земл+и", "+Аду", "Семёна", "Стёп", "Королёв",
            "Королёва", "Королёве", "Королёву", "Фёдора", "Фёдору", "Лёня", "Лёни", "Лёней"
        )

        for (raw in properNounsRaw) {
            val value = plusRegex.replace(raw) { match ->
                match.groupValues[1] + "\u0301"
            }.lowercase()
            val key = raw.replace("+", "").replace("ё", "е").replace("Ё", "Е").lowercase()
            put(key, value)
        }
    }

    private val wordRegex = Regex("[а-яА-ЯёЁ-]+")

    private val yearPattern = Regex("\\b(\\d{1,4})(?:-[а-яА-ЯёЁ]{1,3})?\\s+(год[а-яА-ЯёЁ]{0,3})\\b", RegexOption.IGNORE_CASE)

    // ИСПРАВЛЕНО: Убран (?U), вызывавший краш
    private val dativePrepositionRegex = Regex("\\b(к|по)\\s*$", RegexOption.IGNORE_CASE)

    private val yearSuffixMap = mapOf(
        "год"    to mapOf("ый" to "ый",  "ой" to "ой",  "ий" to "ий"),
        "года"   to mapOf("ый" to "ого", "ой" to "ого", "ий" to "ьего"),
        "году"   to mapOf("ый" to "ом",  "ой" to "ом",  "ий" to "ьем"),
        "годе"   to mapOf("ый" to "ом",  "ой" to "ом",  "ий" to "ьем"),
        "годом"  to mapOf("ый" to "ым",  "ой" to "ым",  "ий" to "ьим"),
        "годы"   to mapOf("ый" to "ые",  "ой" to "ые",  "ий" to "ьи"),
        "годов"  to mapOf("ый" to "ых",  "ой" to "ых",  "ий" to "ьих"),
        "годам"  to mapOf("ый" to "ым",  "ой" to "ым",  "ий" to "ьим"),
        "годами" to mapOf("ый" to "ыми", "ой" to "ыми", "ий" to "ьими"),
        "годах"  to mapOf("ый" to "ых",  "ой" to "ых",  "ий" to "ьих")
    )

    // 1. Закрывающая скобка + знак препинания (сохраняем знак и ставим пробел)
// 1. Закрывающая скобка + знак препинания (самое важное для интонации)
    private val regexCloseParenPunct = Regex("\\s*\\)\\s*([,.!?])\\s*")

    // 2. Скобки на стыке с текстом -> превращаем в запятые (для пауз в речи)
    private val regexParenToComma = Regex("(?<=[а-яА-ЯёЁa-zA-Z0-9])\\s*\\(\\s*|\\s*\\)\\s*(?=[а-яА-ЯёЁa-zA-Z0-9])")

    // 3. Все оставшиеся скобки -> удаляем
    private val regexLeftoverParens = Regex("[()]")

    suspend fun loadDictionary() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        try {
            context.assets.open("yo.dat").use { inputStream ->
                GZIPInputStream(inputStream).use { gzipStream ->
                    BufferedReader(InputStreamReader(gzipStream, "UTF-8")).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            val wordYo = line.trim().lowercase()
                            if (wordYo.isNotEmpty()) {
                                val wordE = wordYo.replace('ё', 'е')
                                yoMap[wordE] = wordYo
                            }
                            line = reader.readLine()
                        }
                    }
                }
            }
            isLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun normalize(text: String): String {
        var result = text

        result = handleParentheses(result)
        result = yearPattern.replace(result) { match -> replaceYears(match, result) }

        result = wordRegex.replace(result) { matchResult ->
            val word = matchResult.value

            val hasUpperCase = word.any { it.isUpperCase() }
            val lowWord = if (hasUpperCase) word.lowercase() else word
            val isCapitalized = word.isNotEmpty() && word[0].isUpperCase()

            // 1. ОДИНАРНЫЙ ПОИСК для наречий
            val adverb = adverbFixes[lowWord]
            if (adverb != null) {
                return@replace restoreCase(word, adverb)
            }

            if (word.contains('-')) {
                val parts = word.split('-')
                val newParts = parts.mapIndexed { index, part ->
                    val partHasUpper = part.any { it.isUpperCase() }
                    val lowPart = if (partHasUpper) part.lowercase() else part
                    val partCap = part.isNotEmpty() && part[0].isUpperCase()

                    // ОДИНАРНЫЙ ПОИСК для частей слова
                    val compound = compoundPrefixes[lowPart]
                    val properNoun = properNounsMap[lowPart]
                    val yoWord = yoMap[lowPart]

                    if (index != parts.lastIndex && compound != null) {
                        restoreCase(part, compound)
                    } else if (partCap && properNoun != null) {
                        restoreCase(part, properNoun)
                    } else if (isLoaded && part.contains('е', ignoreCase = true) && yoWord != null) {
                        restoreCase(part, yoWord)
                    } else {
                        part
                    }
                }
                newParts.joinToString("-")
            } else {
                // ОДИНАРНЫЙ ПОИСК для целых слов
                val properNoun = properNounsMap[lowWord]
                val yoWord = yoMap[lowWord]

                if (isCapitalized && properNoun != null) {
                    restoreCase(word, properNoun)
                } else if (isLoaded && word.contains('е', ignoreCase = true) && yoWord != null) {
                    restoreCase(word, yoWord)
                } else {
                    word
                }
            }
        }

        return result
    }

    private fun replaceYears(match: MatchResult, fullText: String): String {
        val numStr = match.groupValues[1]
        val godWordRaw = match.groupValues[2]
        val godWordLow = godWordRaw.lowercase()
        val yearNum = numStr.toIntOrNull() ?: return match.value

        val ordinalNominative = YearToWords.getOrdinal(yearNum)
        var targetRules = yearSuffixMap[godWordLow] ?: yearSuffixMap["год"]!!
        var godWordProcessed = godWordRaw

        if (godWordLow == "году") {
            // ОПТИМИЗАЦИЯ: берем только последние 15 символов перед годом (спасает память на огромных текстах)
            val startIndex = maxOf(0, match.range.first - 15)
            val prefix = fullText.substring(startIndex, match.range.first)

            if (dativePrepositionRegex.containsMatchIn(prefix)) {
                targetRules = mapOf("ый" to "ому", "ой" to "ому", "ий" to "ьему")
                godWordProcessed = restoreCase(godWordRaw, "го\u0301ду")
            }
        }

        val words = ordinalNominative.split(" ").toMutableList()
        var lastWord = words.last()

        for ((baseEnd, newEnd) in targetRules) {
            if (lastWord.endsWith(baseEnd)) {
                lastWord = lastWord.dropLast(baseEnd.length) + newEnd
                break
            }
        }

        words[words.lastIndex] = lastWord
        var normalizedNum = words.joinToString(" ")

        if (match.value.isNotEmpty() && match.value[0].isUpperCase()) {
            normalizedNum = normalizedNum.replaceFirstChar { it.uppercase() }
        }

        return "$normalizedNum $godWordProcessed"
    }

    private fun handleParentheses(text: String): String {
        var res = text
        res = regexCloseParenPunct.replace(res, "$1 ")
        res = regexParenToComma.replace(res, ", ")
        res = regexLeftoverParens.replace(res, "")
        return res.trim()
    }

    private fun restoreCase(original: String, replacement: String): String {
        if (original.isEmpty()) return replacement
        return if (original[0].isUpperCase()) {
            val isAllUpper = original.length > 1 && original.drop(1).all { it.isUpperCase() }
            if (isAllUpper) replacement.uppercase()
            else replacement.substring(0, 1).uppercase() + replacement.substring(1)
        } else {
            replacement
        }
    }
}

private object YearToWords {
    private val cardinals = mapOf(
        1 to "одна", 2 to "две", 3 to "три", 4 to "четыре", 5 to "пять",
        6 to "шесть", 7 to "семь", 8 to "восемь", 9 to "девять",
        10 to "десять", 11 to "одиннадцать", 12 to "двенадцать", 13 to "тринадцать",
        14 to "четырнадцать", 15 to "пятнадцать", 16 to "шестнадцать", 17 to "семнадцать",
        18 to "восемнадцать", 19 to "девятнадцать",
        20 to "двадцать", 30 to "тридцать", 40 to "сорок", 50 to "пятьдесят",
        60 to "шестьдесят", 70 to "семьдесят", 80 to "восемьдесят", 90 to "девяносто",
        100 to "сто", 200 to "двести", 300 to "триста", 400 to "четыреста", 500 to "пятьсот",
        600 to "шестьсот", 700 to "семьсот", 800 to "восемьсот", 900 to "девятьсот"
    )
    private val thousands = mapOf(
        1 to "тысяча", 2 to "две тысячи", 3 to "три тысячи", 4 to "четыре тысячи",
        5 to "пять тысяч", 6 to "шесть тысяч", 7 to "семь тысяч", 8 to "восемь тысяч", 9 to "девять тысяч"
    )
    private val ordinals = mapOf(
        0 to "нулевой", 1 to "первый", 2 to "второй", 3 to "третий", 4 to "четвертый", 5 to "пятый",
        6 to "шестой", 7 to "седьмой", 8 to "восьмой", 9 to "девятый",
        10 to "десять", 11 to "одиннадцать", 12 to "двенадцатый", 13 to "тринадцатый",
        14 to "четырнадцатый", 15 to "пятнадцатый", 16 to "шестнадцать", 17 to "семнадцатый",
        18 to "восемнадцатый", 19 to "девятнадцатый",
        20 to "двадцатый", 30 to "тридцатый", 40 to "сороковой", 50 to "пятидесятый",
        60 to "шестидесятый", 70 to "семидесятый", 80 to "восьмидесятый", 90 to "девяностый",
        100 to "сотый", 200 to "двухсотый", 300 to "трехсотый", 400 to "четырехсотый", 500 to "пятисотый",
        600 to "шестисотый", 700 to "семисотый", 800 to "восьмисотый", 900 to "девятисотый",
        1000 to "тысячный", 2000 to "двухтысячный", 3000 to "трехтысячный", 4000 to "четырехтысячный",
        5000 to "пятитысячный", 6000 to "шеститысячный", 7000 to "семитысячный", 8000 to "восьмитысячный", 9000 to "девятитысячный"
    )

    fun getOrdinal(year: Int): String {
        if (ordinals.containsKey(year)) return ordinals[year]!!

        val parts = mutableListOf<String>()
        val thousandsDigit = year / 1000
        val hundredsDigit = (year % 1000) / 100
        val tensAndUnits = year % 100

        if (thousandsDigit > 0) {
            parts.add(thousands[thousandsDigit]!!)
        }

        if (hundredsDigit > 0) {
            if (tensAndUnits == 0) {
                parts.add(ordinals[hundredsDigit * 100]!!)
            } else {
                parts.add(cardinals[hundredsDigit * 100]!!)
            }
        }

        if (tensAndUnits > 0) {
            if (ordinals.containsKey(tensAndUnits)) {
                parts.add(ordinals[tensAndUnits]!!)
            } else {
                val tens = (tensAndUnits / 10) * 10
                val units = tensAndUnits % 10
                parts.add(cardinals[tens]!!)
                parts.add(ordinals[units]!!)
            }
        }

        return parts.joinToString(" ")
    }
}