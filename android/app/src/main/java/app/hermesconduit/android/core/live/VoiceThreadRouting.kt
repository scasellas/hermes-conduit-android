package app.hermesconduit.android.core.live

/**
 * Words that steer a live call's requests (#451): where a request goes
 * while the call is attached to a chat, "send it to Hermes", how the user
 * answered a held request, and a request to hear a reply again. GPT-Live
 * has one delegation channel, so its words decide; Gemini and Grok pick a
 * tool instead. Pure, matched as iOS `VoiceThreadRouting` does.
 */
object VoiceThreadRouting {
    /** Phrases that keep work out of the chat, as a background job. Whole words only. */
    val backgroundPhrases = listOf(
        "in the background", "as a background job", "in a background job", "start a background job",
        "in a separate chat", "in a new chat", "在后台", "后台运行", "后台任务",
    )

    /** "Read me the last reply" asks for what Hermes already said. */
    val lastReplyPhrases = listOf(
        "last reply", "last response", "latest reply", "latest response", "previous reply", "full reply",
        "said last", "last said", "last message", "latest message", "previous message", "full message",
        "last answer", "latest answer", "previous answer", "full answer", "full response", "whole reply",
        "whole answer", "entire reply", "上一条回复", "最后一条回复",
    )
    val readVerbs = listOf("read", "repeat", "tell me", "let me hear", "hear", "play back", "say")
    val politePrefixes = listOf("please ", "can you ", "could you ", "would you ", "hey, ", "hey ", "ok, ", "ok ", "okay, ", "okay ", "so ")
    val readLeadIns = listOf(
        "no ", "nope ", "i want you to ", "i'd like you to ", "i would like you to ", "i need you to ",
        "you need to ", "you have to ", "i asked you to ", "i said ", "i want to ", "i'd like to ",
        "just ", "now ", "actually ", "well ",
    )
    val repeatRequests = listOf(
        "repeat that", "repeat it", "repeat yourself",
        "repeat what you said", "repeat what you just said", "repeat exactly what you said",
        "repeat what hermes said",
        "say that again", "say it again",
        "read that again", "read it again", "read that back", "read it back", "read that out", "read it out",
    )
    val repeatTrailers = setOf(
        "please", "again", "exactly", "verbatim", "word", "for", "back", "out", "loud",
        "in", "full", "to", "me", "one", "more", "time",
    )
    val fillers = setOf("uh", "uhh", "um", "umm", "uhm", "er", "erm", "ah", "hmm")
    val cjkReadVerbs = listOf("朗读", "读一下", "读给我听", "读给我", "读", "告诉我", "重复")
    val cjkPolitePrefixes = listOf("请", "麻烦", "帮我")
    val cjkTrailers = listOf("。", "！", "吧", "呢", "一下", "给我听", "!", ".")
    val quickWords = listOf("quick", "quickly")

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    /**
     * "Send it to Hermes" (#451): the user already said where it goes. Never
     * when the words before it in its clause say not to or only ask about it.
     * Without the "to" ("Send Hermes") it counts only when nothing follows,
     * and only once the words are `finished`.
     */
    fun saysSendToHermes(words: String, finished: Boolean = true): Boolean {
        val folded = fold(words)
        val send = "\\bsend\\s*(it|this|that|this one|that one)?\\s*(straight|right|over|directly)?"
        val patterns = mutableListOf(send + "\\s*to\\s*hermes\\b")
        if (finished) patterns += send + "[\\s,]*hermes\\s*(now|please)?[\\s.!]*$"
        for (pattern in patterns) {
            for (match in Regex(pattern).findAll(folded)) {
                val clause = folded.substring(0, match.range.first).split(',', '.', ';', ':', '!', '?').lastOrNull().orEmpty()
                val vetoed = clause.split(Regex("\\s+")).any { it in sendVetoes }
                if (!vetoed) return true
            }
        }
        return false
    }

    val sendVetoes = setOf(
        "don't", "dont", "not", "never", "no", "stop", "without", "before", "won't", "can't", "cannot", "shouldn't", "didn't",
        "did", "when", "whether", "if", "what", "why", "how", "where", "who", "should", "shall",
        "has", "have", "was", "were", "remind", "rather", "than", "instead",
    )

    /** How the user answered "send it?" while asking first (#451). */
    sealed interface HeldRequestAnswer {
        /** Yes, led by a yes word or a send. `addition` is the answer when it says more. */
        data class Yes(val addition: String?) : HeldRequestAnswer
        /** Neither yes nor no: a change, unless the model took it as a yes. */
        data class Other(val words: String) : HeldRequestAnswer
        /** No: dropped. With more words, `change` is the answer. */
        data class No(val change: String?) : HeldRequestAnswer
        /** Not yet: it keeps waiting. */
        data class NotYet(val change: String?) : HeldRequestAnswer

        val isAnswer: Boolean get() = this !is Other
        val isBareYes: Boolean get() = this == Yes(null)
        val isBare: Boolean
            get() = when (this) {
                is Yes -> addition == null
                is No -> change == null
                is NotYet -> change == null
                is Other -> false
            }
    }

    val answerNoLeads = listOf(
        "no", "nope", "nah", "don't", "dont", "do not", "never mind", "nevermind",
        "cancel", "forget", "drop", "scrap", "skip", "ditch", "abort", "stop", "leave it", "leave that",
        "nein", "nee", "nö", "auf keinen fall", "lieber nicht", "bloß nicht", "nicht nötig", "nicht senden",
        "nicht schicken", "nicht abschicken", "vergiss es", "vergiss das", "vergiss", "lass es", "lass das",
        "lass mal", "abbrechen", "brich ab", "stopp",
        "nop", "mejor no", "cancela", "cancélalo", "cancelalo", "cancelar", "olvídalo", "olvidalo", "olvida",
        "déjalo", "dejalo", "ni hablar",
        "non", "pas besoin", "pas la peine", "surtout pas", "laisse tomber", "laissez tomber", "oublie",
        "oubliez", "annule", "annulez", "annuler", "arrête", "arrete", "ne l'envoie pas", "ne l'envoyez pas",
        "ne pas l'envoyer", "ne pas envoyer", "n'envoie pas", "n'envoyez pas", "ne le fais pas", "ne fais pas",
        "não", "nao", "negativo", "melhor não", "melhor nao", "esquece", "esqueça", "esqueca",
        "deixa pra lá", "deixa pra la", "cancele",
        "нет", "неа", "не надо", "не нужно", "не стоит", "не отправляй", "не отправляйте", "отмена",
        "отмени", "отмените", "забудь", "забудьте", "забей", "стоп",
        "annulla", "lascia perdere", "lascia stare", "dimenticalo", "dimentica", "meglio di no",
        "nie wysyłaj", "nie", "lepiej nie", "w żadnym wypadku", "anuluj", "zapomnij", "daj spokój", "nieważne",
        "hayır", "yok", "iptal et", "iptal", "boş ver", "vazgeç", "gerek yok", "gönderme", "göndermeyin", "istemiyorum", "istemem", "unut gitsin", "unut",
        "tidak usah", "tidak perlu", "tidak jadi", "tidak", "tak usah", "tak perlu", "tak jadi", "tak bisa", "tak mau", "tak akan", "tak ada", "tak boleh",
        "tak payah", "nggak usah", "nggak perlu", "nggak jadi", "nggak",
        "enggak", "gak usah", "gak perlu", "gak jadi", "gak", "ga usah", "ga jadi", "ga", "jangan dikirim",
        "jangan", "batalkan", "batal", "lupakan",
        "아니요", "아니오", "아뇨", "아니", "됐어요", "됐어", "취소해 주세요", "취소해", "취소", "보내지 마세요",
        "보내지 마", "하지 마세요", "하지 마", "필요 없어요", "필요없어요", "안 돼요", "안 돼", "안돼요", "안돼",
        "싫어요", "싫어", "그만해요", "그만해", "못 해요", "못 해", "못해요", "못해", "그만",
    )
    val answerNotYetLeads = listOf(
        "wait", "hold on", "hang on", "not yet", "not now", "one sec", "one second", "one moment",
        "just a sec", "just a second", "just a moment", "hold it",
        "warte", "wart", "warten sie", "warten", "abwarten", "moment", "einen moment", "einen augenblick", "augenblick",
        "eine sekunde", "sekunde", "noch nicht", "jetzt nicht", "nicht jetzt",
        "espera", "espere", "esperá", "un momento", "un momentito", "un segundo", "un minuto", "un minutito", "momento",
        "todavía no", "todavia no", "aún no", "aun no", "ahora no", "por ahora no", "no todavía",
        "no todavia", "no ahora", "no por ahora",
        "attends", "attendez", "un instant", "une seconde", "une minute", "un moment", "pas encore",
        "pas maintenant", "pas tout de suite",
        "segundo", "minuto", "minutinho", "peraí", "perai", "pera", "aguarda", "aguarde", "ainda não", "ainda nao", "agora não", "agora nao",
        "não agora", "nao agora", "não ainda", "nao ainda",
        "подожди", "подождите", "погоди", "погодите", "секунду", "секундочку", "минуту", "минутку",
        "пока нет", "пока не надо", "не сейчас", "ещё нет", "еще нет", "ещё не", "еще не",
        "aspetta", "aspetti", "un attimo", "un secondo", "non ancora", "non adesso", "non ora", "adesso no",
        "ora no", "ancora no",
        "czekaj", "poczekaj", "zaczekaj", "chwileczkę", "chwilkę", "chwilę", "chwila", "sekundę", "minutę", "momencik", "jeszcze nie", "nie teraz",
        "bir dakika", "bir saniye", "biraz bekle", "bekle", "bekleyin", "dur", "henüz değil", "şimdi değil", "daha değil",
        "tunggu sebentar", "tunggu dulu", "tunggu", "sebentar", "semenit", "sedetik", "belum", "jangan sekarang", "nanti dulu", "nanti saja",
        "nanti aja", "tidak sekarang", "nggak sekarang", "gak sekarang", "tak sekarang",
        "잠깐만요", "잠깐만", "잠깐", "잠시만요", "잠시만", "아직 아니요", "아직이요", "아직", "지금은 아니요",
        "지금 말고", "기다려 주세요", "기다려요", "기다려",
    )
    val answerYesLeads = listOf(
        "no problem", "no worries", "no rush", "yes", "yeah", "yep", "yup", "yea", "ya", "sure", "ok", "okay", "alright", "all right",
        "go ahead", "go for it", "do it", "send it", "send that", "send this", "please", "correct", "right",
        "exactly", "absolutely", "definitely", "of course", "sounds good", "perfect", "great", "fine",
        "good", "that's right", "that's it", "that works", "works for me", "that'll do", "sounds great", "that's great",
        "go", "uh huh", "mhm", "why not",
        "ja", "jawohl", "genau", "na klar", "alles klar", "klar", "sicher", "gerne", "gern",
        "einverstanden", "in ordnung", "kein problem", "sehr gerne", "bitte", "selbstverständlich", "natürlich", "auf jeden fall",
        "mach das", "mach es", "mach's", "machs", "schick es ab", "schick es", "schick's ab", "schick's",
        "schicks", "schick ab", "abschicken", "absenden", "sehr gut", "gut", "super", "prima", "perfekt",
        "no hay problema", "no te preocupes", "no pasa nada", "no hay prisa",
        "sí", "claro que sí", "claro que si", "claro que sim", "claro", "vale", "de acuerdo", "por supuesto", "adelante",
        "venga", "perfecto", "sin problema", "por favor", "hazlo", "envíalo", "envialo", "mándalo", "mandalo",
        "envíaselo", "mándaselo", "me parece bien", "está bien", "esta bien", "muy bien", "genial",
        "listo", "exacto", "correcto", "eso es",
        "oui", "ouais", "d'accord", "bien sûr", "bien sur", "volontiers", "parfait", "allez y", "vas y",
        "pas de problème", "pas de probleme", "pas de souci", "pas de soucis", "aucun problème",
        "aucun probleme", "aucun souci", "envoie le", "envoie la", "envoie ça", "envoie ca",
        "envoyez le", "envoyez la", "s'il te plaît", "s'il te plait", "s'il vous plaît",
        "s'il vous plait", "c'est parfait", "ça marche", "ca marche", "ça me va",
        "ca me va", "très bien", "tres bien", "entendu", "exactement", "évidemment", "evidemment",
        "carrément", "carrement",
        "sim", "com certeza", "certo", "beleza", "tá bom", "ta bom", "está bem",
        "esta bem", "tudo bem", "tudo certo", "pode ser", "pode sim", "pode mandar", "pode enviar",
        "manda ver", "sem problema", "sem problemas", "não tem problema", "nao tem problema",
        "não se preocupe", "nao se preocupe", "fechado", "perfeito", "ótimo", "otimo", "isso mesmo",
        "isso aí", "isso ai", "exato",
        "не вопрос", "да", "ага", "угу", "конечно", "давай", "давайте", "хорошо", "ладно", "ок", "окей",
        "отлично", "супер", "без проблем", "нет проблем", "согласен", "согласна", "верно", "точно", "правильно",
        "именно", "пожалуйста", "отправляй", "отправляйте", "вперёд", "вперед", "годится", "пойдёт", "пойдет",
        "sì", "certamente", "va bene", "d'accordo", "perfetto", "esatto", "giusto", "volentieri",
        "vai pure", "vai avanti", "vai", "procedi", "invialo", "fallo", "assolutamente",
        "ovviamente", "sicuro", "benissimo", "ottimo", "nessun problema", "come no", "per favore",
        "nie ma problemu", "nie ma sprawy", "no jasne", "no pewnie", "no dobra", "no dobrze", "no tak",
        "tak masalah", "tak", "jasne", "pewnie", "na pewno", "oczywiście", "dobrze", "dobra", "okej", "zgoda", "wyślij to",
        "wysyłaj", "śmiało", "proszę", "świetnie", "idealnie", "zgadza się", "w porządku", "racja",
        "dokładnie", "bez problemu",
        "evet", "tamam", "olur", "tabii ki", "tabii", "elbette", "kesinlikle", "peki", "gönder", "yolla",
        "hadi", "olsun", "harika", "mükemmel", "doğru", "aynen", "sorun yok", "problem yok", "lütfen",
        "tidak apa apa", "tidak masalah", "nggak apa apa", "nggak masalah", "gak apa apa", "gak masalah",
        "ga apa apa", "ga masalah", "iya", "oke", "baiklah", "baik", "boleh", "tentu saja",
        "tentu", "pasti", "silakan", "kirim saja", "kirim aja", "kirimkan", "lanjutkan", "lanjut",
        "setuju", "benar", "betul", "bagus", "sip", "mantap", "tolong",
        "네", "예", "응", "좋아요", "좋아", "그래요", "그래", "그럼요", "물론이죠", "물론", "보내 주세요",
        "보내주세요", "보내줘", "부탁해요", "부탁합니다", "알겠어요", "알겠습니다", "오케이",
    )
    val answerRefusalTails = listOf(
        "i'm good", "i'm fine", "i'm ok", "i'm okay", "we're good", "all good", "all set", "as is",
        "it's fine", "it's ok", "it's okay", "that's fine", "that's ok", "that's okay", "no problem",
        "no worries", "no rush",
        "schon gut", "passt schon", "passt so", "alles gut", "ist gut", "ist okay",
        "así está bien", "asi esta bien", "está bien así", "esta bien asi", "estoy bien", "lo mandes",
        "lo envíes", "lo envies", "lo hagas", "hace falta", "es necesario", "no hay problema",
        "no te preocupes", "no pasa nada", "no hay prisa", "sin problema",
        "ça va", "ca va", "c'est bon", "ça ira", "ca ira", "comme ça", "comme ca", "aucun problème",
        "aucun probleme", "aucun souci",
        "precisa não", "precisa nao", "precisa", "tô bem", "to bem", "estou bem", "tá bom", "ta bom",
        "está bom", "esta bom", "tudo bem", "assim mesmo", "sem problema", "sem problemas",
        "всё нормально", "все нормально", "всё хорошо", "все хорошо", "нормально", "так нормально",
        "без проблем",
        "serve", "c'è bisogno", "importa", "fa niente", "va bene così", "va bene", "sto bene",
        "tutto a posto", "a posto", "nessun problema",
        "trzeba", "ma potrzeby", "w porządku", "wszystko dobrze", "jest dobrze", "bez problemu",
        "kalsın", "böyle iyi", "iyiyim",
        "tidak apa apa", "nggak apa apa", "gak apa apa", "ga apa apa", "sudah cukup", "udah cukup", "cukup",
        "tak masalah",
        "괜찮아요", "괜찮습니다",
    )
    val answerJoiners = listOf(
        "but", "actually", "oh", "well", "aber", "doch", "naja", "äh", "ähm", "pero", "bueno", "eh",
        "mais", "bon", "euh", "mas", "então", "entao", "só", "но", "ну", "э", "эм", "ma", "beh", "allora",
        "ehm", "ale", "właściwie", "cóż", "ama", "aslında", "şey", "yani", "tapi", "sebenarnya", "근데",
        "그런데", "하지만", "음",
    )
    val answerTurnWords = setOf(
        "not", "never", "no", "dont", "without", "cannot", "whether", "if", "unless", "what", "why", "how", "where", "who",
        "should", "shall", "wait", "hold", "think", "later", "maybe", "nope", "nah",
        "cancel", "stop", "forget", "drop", "scrap", "skip", "ditch", "abort",
        "nicht", "kein", "keine", "keinen", "keiner", "nichts", "nie", "niemals", "ohne", "ob", "falls",
        "warum", "wieso", "weshalb", "wann", "wo", "wer", "vielleicht", "später", "warte",
        "abbrechen", "vergiss", "überlegen", "nachdenken",
        "nunca", "sin", "quizás", "quizas", "quizá", "quiza", "luego", "después", "despues", "tarde",
        "espera", "cancela", "cancelar", "olvida", "olvídalo", "olvidalo", "qué", "cómo", "cuándo",
        "dónde", "quién", "pensar", "pensarlo",
        "pas", "jamais", "rien", "sans", "peut", "tard", "attends", "attendez", "annule", "annuler",
        "oublie", "pourquoi", "comment", "quand", "où", "quoi", "réfléchir", "reflechir",
        "não", "nao", "sem", "talvez", "depois", "esquece", "cancele", "quê", "onde",
        "не", "нет", "ни", "никогда", "ничего", "без", "если", "ли", "почему", "зачем", "где", "кто",
        "может", "потом", "позже", "подожди", "погоди", "отмена", "отмени", "забудь", "подумать",
        "non", "mai", "niente", "nulla", "senza", "se", "forse", "dopo", "perché", "dove", "chi", "aspetta",
        "annulla", "pensarci",
        "nigdy", "nic", "jeśli", "czy", "może", "później", "potem", "dlaczego", "czemu", "gdzie",
        "kto", "czekaj", "anuluj", "zapomnij", "pomyśleć", "zastanowić",
        "değil", "hiç", "asla", "olmadan", "eğer", "belki", "sonra", "neden", "niye", "nasıl", "nerede",
        "bekle", "iptal", "düşüneyim",
        "tidak", "nggak", "gak", "jangan", "belum", "tanpa", "kalau", "jika", "apakah", "mungkin", "nanti",
        "kenapa", "mengapa", "bagaimana", "mana", "siapa", "tunggu", "batal", "pikir", "pikirkan",
        "안", "못", "말고", "없이", "만약", "왜", "어떻게", "어디", "누가", "나중에", "아마", "잠깐", "생각해",
    )
    val answerFillerWords = setOf(
        "thanks", "thank", "you", "please", "yet", "now", "anymore", "it", "that", "this", "send",
        "sending", "to", "hermes", "do", "don't", "dont", "not", "no", "yes", "just", "right", "a",
        "the", "moment", "second", "sec", "one", "wait", "hold", "on", "i", "said", "go", "ahead",
        "and", "ok", "okay", "sure", "that's", "all", "about", "minute",
        "ja", "danke", "bitte", "schön", "sehr", "es", "das", "an", "jetzt", "noch", "mal", "eine", "einen",
        "sekunde", "augenblick", "kurz",
        "sí", "que", "gracias", "por", "favor", "lo", "eso", "ahora", "todavía", "todavia", "un", "momento", "segundo", "poco", "poquito",
        "enviar", "mandar", "envíes", "envies", "mandes",
        "oui", "merci", "beaucoup", "s'il", "te", "vous", "plaît", "plait", "le", "la", "ça", "ca", "à",
        "maintenant", "encore", "une", "seconde", "instant", "peu", "envoie", "envoyer", "envoyez", "tout",
        "sim", "obrigado", "obrigada", "valeu", "isso", "agora", "ainda", "para", "pro", "ao", "o", "um", "pouco", "pouquinho", "aí",
        "mande", "envie", "manda", "envia",
        "да", "спасибо", "пожалуйста", "это", "его", "сейчас", "ещё", "еще", "гермес", "гермесу", "немного", "отправлять", "отправляй",
        "отправь", "отправить",
        "grazie", "mille", "per", "pure", "adesso", "ora", "ancora", "attimo", "sì", "inviare", "inviarlo",
        "inviarla", "mandare", "mandarlo", "mandarla",
        "tak", "dzięki", "dziękuję", "proszę", "teraz", "jeszcze", "wysyłaj", "wysyłać", "wysłać", "tego",
        "evet", "teşekkürler", "teşekkür", "ederim", "sağ", "ol", "lütfen", "şimdi", "bunu", "onu",
        "ya", "terima", "kasih", "makasih", "saja", "aja", "dong", "deh", "sekarang", "itu", "ini", "ke", "dulu", "kirim", "dikirim", "mau", "bisa",
        "네", "감사합니다", "고마워요", "고마워", "주세요", "부탁해요", "지금", "그거", "이거",
    )
    val answerNegations = setOf(
        "not", "never", "dont", "nothing",
        "nicht", "kein", "keine", "keinen", "nichts", "nie", "niemals",
        "nunca", "nada", "ni", "pas", "rien", "jamais", "não", "nao", "не", "нет", "ничего", "никогда",
        "non", "mai", "niente", "nulla", "nigdy", "nic", "değil", "yok", "hiç", "asla",
        "tidak", "nggak", "enggak", "gak", "ga", "jangan", "belum", "bukan", "안", "못",
    )

    private fun words(lead: String) = lead.split(' ').filter { it.isNotEmpty() }
    private val answerNoLeadWords = answerNoLeads.map(::words)

    /** Positive leads a no lead starts ("no problem", "no todavía"): a yes or a not yet, not a no. */
    private val answerLeadsStartingWithNo: List<List<String>> = (answerYesLeads + answerNotYetLeads).mapNotNull { lead ->
        val leadWords = words(lead)
        val startsWithNo = leadWords.size > 1 && answerNoLeadWords.any { leadWords.startsWith(it) }
        if (startsWithNo) leadWords else null
    }

    val answerBareSendLeads = setOf(
        "abschicken", "absenden", "pode mandar", "pode enviar", "отправляй", "отправляйте", "wysyłaj", "gönder",
        "yolla", "kirimkan",
    )

    /** The Polish yes that is also the Indonesian "not". */
    const val ANSWER_YES_ALSO_NOT = "tak"

    private fun List<String>.startsWith(prefix: List<String>) = size >= prefix.size && subList(0, prefix.size) == prefix

    private fun negates(words: List<String>) = words.any { it in answerNegations || it.endsWith("n't") }

    private val wordSplit = Regex("[^\\p{L}\\p{N}']+")

    fun heldRequestAnswer(answer: String): HeldRequestAnswer {
        val spoken = answer.trim()
        cjkHeldRequestAnswer(spoken)?.let { return it }
        var words = fold(spoken).split(wordSplit).filter { it.isNotEmpty() && it !in fillers }.toMutableList()
        val saysNot = Regex("(^|[^\\p{L}\\p{N}'])$ANSWER_YES_ALSO_NOT\\s+[\\p{L}\\p{N}]").containsMatchIn(fold(spoken))
        val yesLeads = if (saysNot) answerYesLeads.filter { it != ANSWER_YES_ALSO_NOT } else answerYesLeads
        fun more(rest: List<String>): String? = if (rest.any { it !in answerFillerWords }) spoken else null
        fun dropLead(leads: List<String>): Boolean {
            for (lead in leads) {
                val leadWords = words(lead)
                if (words.startsWith(leadWords)) {
                    repeat(leadWords.size) { words.removeAt(0) }
                    return true
                }
            }
            return false
        }
        fun startsWithNoLead() = answerLeadsStartingWithNo.none { words.startsWith(it) } && answerNoLeadWords.any { words.startsWith(it) }
        fun dropNoLead() = startsWithNoLead() && dropLead(answerNoLeads)
        fun change(rest: List<String>): String? = if (negates(rest)) null else more(rest)
        fun dropFiller(): Boolean {
            val first = words.firstOrNull() ?: return false
            if (first !in answerFillerWords) return false
            words.removeAt(0)
            return true
        }
        fun refusal(): HeldRequestAnswer {
            val rest = words.toList()
            while (dropNoLead() || dropLead(answerNotYetLeads) || dropLead(answerRefusalTails) || dropFiller()) Unit
            return HeldRequestAnswer.No(if (negates(rest)) null else more(words))
        }
        fun notYet(): HeldRequestAnswer {
            while (dropLead(answerJoiners) || dropLead(answerNotYetLeads)) Unit
            if (dropNoLead()) return refusal()
            return HeldRequestAnswer.NotYet(change(words))
        }
        while (dropLead(answerJoiners)) Unit
        if (dropNoLead()) return refusal()
        if (dropLead(answerNotYetLeads)) return notYet()
        val unanswered = words.toList()
        if (!dropLead(yesLeads)) {
            // "Send to Hermes", or just "Send": a yes with nothing more.
            if (words == listOf("send") || saysSendToHermes(spoken)) return HeldRequestAnswer.Yes(more(words))
            return HeldRequestAnswer.Other(spoken)
        }
        val lead = unanswered.dropLast(words.size).joinToString(" ")
        if (lead in answerBareSendLeads && more(words) != null && !saysSendToHermes(spoken)) return HeldRequestAnswer.Other(spoken)
        while (!startsWithNoLead() && (dropLead(yesLeads) || dropLead(answerJoiners))) Unit
        if (dropNoLead()) return refusal()
        if (dropLead(answerNotYetLeads)) return notYet()
        val turned = words.any { word ->
            word in answerTurnWords || word.endsWith("n't") || (saysNot && word == ANSWER_YES_ALSO_NOT) ||
                word.substringBefore('\'') in answerTurnWords
        }
        if (words.isNotEmpty() && (turned || spoken.contains("?"))) return HeldRequestAnswer.Other(spoken)
        // "Yes, send it to Hermes": the send is the yes.
        val rest = if (saysSendToHermes(words.joinToString(" "))) emptyList() else words
        return HeldRequestAnswer.Yes(more(rest))
    }

    val cjkAnswerNoLeads = listOf(
        "いいえ", "いえ", "ううん", "いや", "やめて", "結構です", "けっこうです", "要らない", "いらない", "キャンセル",
        "不要", "不用", "不了", "不是", "不对", "不行", "不需要", "算了", "取消", "别发",
    )
    val cjkAnswerNotYetLeads = listOf(
        "ちょっと待って", "待って", "まだ", "ちょっと", "等等", "等一下", "稍等", "先等", "先别", "先不", "还没", "暂时不",
    )
    val cjkAnswerYesLeads = listOf(
        "はい", "うん", "ええ", "お願いします", "オッケー", "オーケー", "どうぞ", "送って", "送信して", "そうして",
        "好的", "好啊", "好呀", "好吧", "行吧", "行啊", "是的", "对的", "当然", "没问题", "发吧", "发送吧",
    )
    val cjkAnswerClauseYes = setOf("好", "对", "嗯", "行", "是", "可以", "お願い")
    val cjkAnswerClauseNo = setOf("不", "别")
    val cjkAnswerFillers = listOf(
        "ありがとうございます", "ありがとう", "お願いします", "お願い", "ください", "どうも", "です", "ます", "ね", "よ",
        "送って", "没问题", "谢谢", "谢了", "发吧", "发送", "请", "吧", "了", "啊", "呀", "呢", "的",
    ).sortedByDescending { it.length }
    val cjkAnswerTurns = listOf("不", "没", "别", "但", "等", "想", "考虑", "でも", "けど", "やめ", "待", "ない", "ません", "考え")

    /** A Japanese or Chinese answer, or null for any other. */
    private fun cjkHeldRequestAnswer(spoken: String): HeldRequestAnswer? {
        val clauses = spoken.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val all = clauses.joinToString("")
        var rest = all
        fun dropLead(leads: List<String>): Boolean {
            val lead = leads.firstOrNull { rest.startsWith(it) } ?: return false
            rest = rest.drop(lead.length)
            return true
        }
        fun dropClause(answers: Set<String>): Boolean {
            val first = clauses.firstOrNull() ?: return false
            if (rest != all) return false
            val character = first.first()
            val repeated = answers.contains(character.toString()) && first.all { it == character }
            if (first !in answers && !repeated) return false
            rest = rest.drop(first.length)
            return true
        }
        fun left() = cjkAnswerFillers.fold(rest) { acc, filler -> acc.replace(filler, "") }
        fun turned(words: String) = cjkAnswerTurns.any { words.contains(it) }
        fun change(): String? {
            val words = left()
            return if (words.isEmpty() || turned(words)) null else spoken
        }
        fun notYet(): HeldRequestAnswer {
            if (!dropLead(cjkAnswerNoLeads)) return HeldRequestAnswer.NotYet(change())
            while (dropLead(cjkAnswerNoLeads)) Unit
            return HeldRequestAnswer.No(change())
        }
        if (dropClause(cjkAnswerClauseNo) || dropLead(cjkAnswerNoLeads)) {
            while (dropLead(cjkAnswerNoLeads)) Unit
            return HeldRequestAnswer.No(change())
        }
        if (dropLead(cjkAnswerNotYetLeads)) return notYet()
        if (!(dropClause(cjkAnswerClauseYes) || dropLead(cjkAnswerYesLeads))) return null
        while (dropLead(cjkAnswerYesLeads)) Unit
        if (dropLead(cjkAnswerNoLeads)) return HeldRequestAnswer.No(change())
        if (dropLead(cjkAnswerNotYetLeads)) return notYet()
        val words = left()
        if (words.isEmpty()) return HeldRequestAnswer.Yes(null)
        val asks = spoken.contains("?") || spoken.contains("？") || rest.endsWith("吗") || rest.endsWith("か")
        if (turned(words) || asks) return HeldRequestAnswer.Other(spoken)
        return HeldRequestAnswer.Yes(spoken)
    }

    /** The user asked for separate work while a job runs, so it isn't a follow-up to that job. */
    val newWorkPhrases = listOf(
        "new job", "another job", "separate job", "second job", "new request", "another request",
        "separately", "in the meantime", "meanwhile", "in parallel", "at the same time",
        "while that runs", "while that's running", "while it runs", "while it's running",
        "while you wait", "while we wait",
    )

    fun wantsNewWork(request: String): Boolean {
        val folded = fold(request)
        return newWorkPhrases.any { contains(folded, it) } || wantsBackgroundJob(request)
    }

    fun wantsBackgroundJob(request: String): Boolean {
        val folded = fold(request)
        return backgroundPhrases.any { contains(folded, it) } || startsQuick(folded)
    }

    private val quickMarker = Regex("^(quickly|quick)(\\s*[:,，：]\\s*|\\s+)|^(快速|快)\\s*[:,，：]\\s*", RegexOption.IGNORE_CASE)

    /** The request without the leading "quick" that routed it. */
    fun removingQuickMarker(request: String): String {
        val trimmed = request.trim()
        if (!startsQuick(fold(trimmed))) return trimmed
        val marker = quickMarker.find(trimmed) ?: return trimmed
        val rest = trimmed.substring(marker.range.last + 1)
        return rest.ifEmpty { trimmed }
    }

    private fun startsQuick(folded: String): Boolean {
        var request = folded.trim()
        while (true) {
            val prefix = politePrefixes.firstOrNull { request.startsWith(it) } ?: break
            request = request.drop(prefix.length)
        }
        for (word in quickWords) {
            if (!request.startsWith(word)) continue
            val rest = request.drop(word.length)
            val next = rest.firstOrNull() ?: continue
            if (isWordChar(next)) continue
            // "Quick question, …" is a figure of speech, not a request for a job.
            val asked = rest.dropWhile { !isWordChar(it) }
            if (asked.startsWith("question") && asked.drop("question".length).firstOrNull()?.let { !isWordChar(it) } != false) continue
            if (asked.isNotEmpty()) return true
        }
        var cjk = folded.filter { !it.isWhitespace() }
        while (true) {
            val prefix = cjkPolitePrefixes.firstOrNull { cjk.startsWith(it) } ?: break
            cjk = cjk.drop(prefix.length)
        }
        for (word in listOf("快速", "快")) {
            if (!cjk.startsWith(word)) continue
            val rest = cjk.drop(word.length)
            val next = rest.firstOrNull() ?: continue
            if (next in "，,：:" && rest.length > 1) return true
        }
        return false
    }

    fun wantsLastReply(request: String): Boolean {
        val folded = withoutFillers(fold(request).replace(Regex("[,.!?;:]"), " "))
        if (wantsRepeat(folded) || wantsWhatWasSaid(folded)) return true
        if (lastReplyPhrases.none { contains(folded, it) }) return false
        if (wantsCJKLastReply(folded)) return true
        val request = withoutLeadIns(folded)
        val ledByReadVerb = readVerbs.any { verb ->
            if (!request.startsWith(verb)) return@any false
            val next = request.drop(verb.length).firstOrNull()
            next == null || !isWordChar(next)
        }
        return ledByReadVerb && endsWithTheReply(request)
    }

    val lastReplyTrailers = repeatTrailers + setOf(
        "aloud", "now", "from", "hermes",
        "that", "sent", "wrote", "gave", "said", "posted", "you", "just",
        "without", "sending", "asking", "it", "first",
    )
    val lastReplyConjunctions = setOf(
        "and", "then", "also", "but", "or", "about",
        "think", "thought", "thoughts", "feel", "felt", "opinion", "take",
        "why", "how", "mean", "meant",
    )
    val lastReplyPlaces = listOf(listOf("from", "the", "chat"), listOf("in", "the", "chat"), listOf("in", "this", "chat"))

    private fun endsWithTheReply(request: String): Boolean {
        val words = request.split(wordSplit).filter { it.isNotEmpty() }
        var phrase: IntRange? = null
        for (candidate in lastReplyPhrases) {
            val phraseWords = words(candidate)
            if (phraseWords.isEmpty() || words.size < phraseWords.size) continue
            for (start in (words.size - phraseWords.size) downTo 0) {
                if (words.subList(start, start + phraseWords.size) == phraseWords) {
                    if (start + phraseWords.size > (phrase?.let { it.last + 1 } ?: 0)) phrase = start until start + phraseWords.size
                    break
                }
            }
        }
        val found = phrase ?: return false
        if (words.subList(0, found.first).any { it in lastReplyConjunctions }) return false
        val tail = words.subList(found.last + 1, words.size).toMutableList()
        for (place in lastReplyPlaces) {
            if (tail.size < place.size) continue
            val start = (0..(tail.size - place.size)).firstOrNull { tail.subList(it, it + place.size) == place } ?: continue
            repeat(place.size) { tail.removeAt(start) }
            break
        }
        return tail.all { it in lastReplyTrailers }
    }

    /** A request to hear it again that ends what the user said, after sentences or a clause of their own. */
    fun endsWithLastReplyRequest(words: String): Boolean {
        val parts = words.split(Regex("[\\n.!?。！？,，…]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        return (1 until parts.size).any { start -> wantsLastReply(parts.subList(start, parts.size).joinToString(", ")) }
    }

    private val whatWasSaidPattern = Regex("^(read|repeat)( it| that)?( back| out)?( to)?( me| us)? what ((we|you|he|she|it|they|hermes) (just )?(said|wrote|sent|replied|answered)|was (just )?said)\\b")

    private fun wantsWhatWasSaid(folded: String): Boolean {
        val request = withoutLeadIns(folded)
        val match = whatWasSaidPattern.find(request) ?: return false
        return request.substring(match.range.last + 1).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.all { it in repeatTrailers }
    }

    private fun wantsRepeat(folded: String): Boolean {
        val request = withoutLeadIns(folded)
        return repeatRequests.any { phrase ->
            if (!request.startsWith(phrase)) return@any false
            val rest = request.drop(phrase.length)
            val next = rest.firstOrNull()
            if (next != null && isWordChar(next)) return@any false
            rest.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.all { it in repeatTrailers }
        }
    }

    private fun withoutLeadIns(folded: String): String {
        var request = folded.trim()
        while (true) {
            val prefix = (politePrefixes + readLeadIns).firstOrNull { request.startsWith(it) } ?: break
            request = request.drop(prefix.length).trimStart()
        }
        return request
    }

    private fun withoutFillers(folded: String): String =
        folded.split(' ').filter { it.isNotEmpty() && it.trim { c -> !isWordChar(c) && c != '\'' }.let { w -> w !in fillers } }.joinToString(" ")

    private fun wantsCJKLastReply(folded: String): Boolean {
        var request = folded.filter { !it.isWhitespace() }
        while (true) {
            val prefix = cjkPolitePrefixes.firstOrNull { request.startsWith(it) } ?: break
            request = request.drop(prefix.length)
        }
        val verb = cjkReadVerbs.firstOrNull { request.startsWith(it) } ?: return false
        request = request.drop(verb.length)
        while (true) {
            val trailer = cjkTrailers.firstOrNull { request.endsWith(it) } ?: break
            request = request.dropLast(trailer.length)
        }
        if (request.startsWith("我")) request = request.drop(1)
        if (request.startsWith("hermes的")) request = request.drop("hermes的".length)
        return lastReplyPhrases.any { it == request }
    }

    /** Lowercased, a curly apostrophe straight, and "İ" lowercased to a plain "i". */
    fun fold(text: String): String = text.lowercase().replace('’', '\'').replace("i̇", "i")

    /** Whole-word match for Latin phrases; CJK phrases have no spaces, so a substring is a match. */
    fun contains(text: String, phrase: String): Boolean {
        if (!phrase.all { it.code < 128 }) return text.contains(phrase)
        var from = 0
        while (true) {
            val index = text.indexOf(phrase, from)
            if (index < 0) return false
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + phrase.length)
            if ((before == null || !isWordChar(before)) && (after == null || !isWordChar(after))) return true
            from = index + 1
        }
    }
}
