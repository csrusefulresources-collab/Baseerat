package com.baseerat.vision.util

/**
 * Offline English -> Urdu dictionary for the most common ML Kit / ImageNet style labels.
 * Unknown labels are returned unchanged (English), which is still spoken correctly by
 * most TTS engines. Also used in reverse so the Urdu question "کتا ہے؟" can be matched.
 */
object LabelTranslator {

    private val ur: Map<String, String> = mapOf(
        "person" to "شخص", "people" to "لوگ", "face" to "چہرہ", "man" to "آدمی", "woman" to "عورت",
        "boy" to "لڑکا", "girl" to "لڑکی", "child" to "بچہ", "baby" to "شیر خوار بچہ", "crowd" to "ہجوم",
        "smile" to "مسکراہٹ", "hair" to "بال", "beard" to "داڑھی", "hand" to "ہاتھ", "finger" to "انگلی",
        "dog" to "کتا", "cat" to "بلی", "bird" to "پرندہ", "fish" to "مچھلی", "horse" to "گھوڑا",
        "cow" to "گائے", "sheep" to "بھیڑ", "goat" to "بکری", "elephant" to "ہاتھی", "lion" to "شیر",
        "tiger" to "چیتا", "monkey" to "بندر", "rabbit" to "خرگوش", "butterfly" to "تتلی", "insect" to "کیڑا",
        "snake" to "سانپ", "animal" to "جانور", "pet" to "پالتو جانور", "duck" to "بطخ", "chicken" to "مرغی",
        "tree" to "درخت", "flower" to "پھول", "plant" to "پودا", "grass" to "گھاس", "leaf" to "پتہ",
        "forest" to "جنگل", "garden" to "باغ", "mountain" to "پہاڑ", "sky" to "آسمان", "cloud" to "بادل",
        "sun" to "سورج", "moon" to "چاند", "sea" to "سمندر", "ocean" to "سمندر", "water" to "پانی",
        "river" to "دریا", "lake" to "جھیل", "beach" to "ساحل", "sand" to "ریت", "snow" to "برف",
        "rain" to "بارش", "road" to "سڑک", "street" to "گلی", "bridge" to "پل", "field" to "کھیت",
        "building" to "عمارت", "house" to "گھر", "home" to "گھر", "room" to "کمرہ", "door" to "دروازہ",
        "window" to "کھڑکی", "wall" to "دیوار", "roof" to "چھت", "floor" to "فرش", "stairs" to "سیڑھیاں",
        "kitchen" to "باورچی خانہ", "bathroom" to "غسل خانہ", "bedroom" to "خواب گاہ", "mosque" to "مسجد",
        "tower" to "مینار", "monument" to "یادگار", "city" to "شہر", "market" to "بازار", "shop" to "دکان",
        "table" to "میز", "chair" to "کرسی", "sofa" to "صوفہ", "couch" to "صوفہ", "bed" to "بستر",
        "desk" to "ڈیسک", "shelf" to "شیلف", "cabinet" to "الماری", "lamp" to "لیمپ", "fan" to "پنکھا",
        "mirror" to "آئینہ", "curtain" to "پردہ", "pillow" to "تکیہ", "carpet" to "قالین", "rug" to "قالین",
        "clock" to "گھڑی", "watch" to "گھڑی", "key" to "چابی", "lock" to "تالا", "box" to "ڈبہ",
        "bag" to "تھیلا", "backpack" to "بستہ", "suitcase" to "سوٹ کیس", "wallet" to "بٹوا",
        "phone" to "فون", "mobile phone" to "موبائل فون", "smartphone" to "اسمارٹ فون", "computer" to "کمپیوٹر",
        "laptop" to "لیپ ٹاپ", "keyboard" to "کی بورڈ", "mouse" to "ماؤس", "monitor" to "مانیٹر",
        "television" to "ٹی وی", "screen" to "اسکرین", "camera" to "کیمرا", "headphones" to "ہیڈ فون",
        "speaker" to "اسپیکر", "remote control" to "ریموٹ", "cable" to "تار", "battery" to "بیٹری",
        "book" to "کتاب", "paper" to "کاغذ", "pen" to "قلم", "pencil" to "پنسل", "notebook" to "کاپی",
        "text" to "متن", "poster" to "پوسٹر", "sign" to "نشان", "signage" to "سائن بورڈ", "newspaper" to "اخبار",
        "money" to "پیسے", "coin" to "سکہ", "cash" to "نقدی", "card" to "کارڈ", "ticket" to "ٹکٹ",
        "car" to "کار", "vehicle" to "گاڑی", "truck" to "ٹرک", "bus" to "بس", "motorcycle" to "موٹر سائیکل",
        "bicycle" to "سائیکل", "bike" to "سائیکل", "rickshaw" to "رکشہ", "train" to "ٹرین", "airplane" to "ہوائی جہاز",
        "aircraft" to "ہوائی جہاز", "boat" to "کشتی", "ship" to "جہاز", "wheel" to "پہیہ", "tire" to "ٹائر",
        "traffic" to "ٹریفک", "traffic light" to "ٹریفک سگنل",
        "food" to "کھانا", "meal" to "کھانا", "dish" to "ڈش", "bread" to "روٹی", "rice" to "چاول",
        "fruit" to "پھل", "vegetable" to "سبزی", "apple" to "سیب", "banana" to "کیلا", "orange" to "سنگترہ",
        "mango" to "آم", "tomato" to "ٹماٹر", "potato" to "آلو", "onion" to "پیاز", "egg" to "انڈا",
        "meat" to "گوشت", "pizza" to "پیزا", "burger" to "برگر", "cake" to "کیک", "dessert" to "میٹھا",
        "sweet" to "مٹھائی", "tea" to "چائے", "coffee" to "کافی", "milk" to "دودھ", "juice" to "جوس",
        "drink" to "مشروب", "bottle" to "بوتل", "cup" to "کپ", "mug" to "مگ", "glass" to "گلاس",
        "plate" to "پلیٹ", "bowl" to "پیالہ", "spoon" to "چمچ", "fork" to "کانٹا", "knife" to "چھری",
        "tableware" to "برتن", "cookware" to "برتن", "pot" to "ہانڈی", "pan" to "کڑاہی",
        "shirt" to "قمیض", "clothing" to "کپڑے", "dress" to "لباس", "jeans" to "جینز", "shoe" to "جوتا",
        "footwear" to "جوتے", "hat" to "ٹوپی", "cap" to "ٹوپی", "glasses" to "عینک", "sunglasses" to "دھوپ کی عینک",
        "jewellery" to "زیورات", "jewelry" to "زیورات", "ring" to "انگوٹھی", "necklace" to "ہار",
        "toy" to "کھلونا", "ball" to "گیند", "game" to "کھیل", "guitar" to "گٹار", "piano" to "پیانو",
        "medicine" to "دوا", "tablet" to "گولی", "pill" to "گولی", "syringe" to "انجکشن",
        "tool" to "اوزار", "hammer" to "ہتھوڑا", "scissors" to "قینچی", "umbrella" to "چھتری",
        "fire" to "آگ", "light" to "روشنی", "shadow" to "سایہ", "night" to "رات", "sunset" to "غروب آفتاب"
    )

    private val reverse: Map<String, String> by lazy { ur.entries.associate { it.value to it.key } }

    fun translate(label: String, urdu: Boolean): String {
        if (!urdu) return label
        return ur[label.trim().lowercase()] ?: label
    }

    fun translateAll(labels: List<String>, urdu: Boolean): List<String> =
        labels.map { translate(it, urdu) }

    /** All English keys whose English or Urdu name appears in the question text. */
    fun findMentioned(question: String): List<String> {
        val q = question.lowercase()
        val tokens = q.split(Regex("[\\s،۔?؟,.!]+")).filter { it.isNotEmpty() }.toSet()
        val hits = LinkedHashSet<String>()
        for ((en, u) in ur) {
            val enRegex = Regex("\\b" + Regex.escape(en) + "s?\\b")
            if (enRegex.containsMatchIn(q) || tokens.contains(u)) hits.add(en)
        }
        return hits.toList()
    }

    fun urduFor(en: String): String? = ur[en]
    fun englishFor(urdu: String): String? = reverse[urdu]
}
