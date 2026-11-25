/// Purposefully simple Lua highlighter, lye will handle the real highlighting once it sends them down,
/// but to avoid visible latency, we highlight the file ourselves until lye sends down the real highlighting.

package com.lasajar.lua

import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as C
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

private object LuaTokens {
    val KEYWORD = IElementType("KEYWORD", LuaLanguage)
    val COMMENT = IElementType("COMMENT", LuaLanguage)
    val STRING = IElementType("STRING", LuaLanguage)
    val NUMBER = IElementType("NUMBER", LuaLanguage)
    val IDENT = IElementType("IDENT", LuaLanguage)
    val OTHER = IElementType("OTHER", LuaLanguage)
}

private val KEYWORDS = setOf(
    "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
    "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
)

class LuaLexer : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var start = 0
    private var tokEnd = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer; end = endOffset; tokEnd = startOffset; advance()
    }

    override fun getState() = 0
    override fun getTokenType() = type
    override fun getTokenStart() = start
    override fun getTokenEnd() = tokEnd
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    override fun advance() {
        start = tokEnd
        if (start >= end) { type = null; return }
        var i = start
        val c = buf[i]
        type = when {
            c.isWhitespace() -> { while (i < end && buf[i].isWhitespace()) i++; TokenType.WHITE_SPACE }
            c == '-' && i + 1 < end && buf[i + 1] == '-' -> {
                i = longBracketEnd(i + 2) ?: (i + 2).let { j -> var k = j; while (k < end && buf[k] != '\n') k++; k }
                LuaTokens.COMMENT
            }
            c == '"' || c == '\'' -> {
                i++
                while (i < end && buf[i] != c && buf[i] != '\n') { if (buf[i] == '\\') i++; i++ }
                if (i < end && buf[i] == c) i++
                LuaTokens.STRING
            }
            c == '[' && longBracketEnd(i) != null -> { i = longBracketEnd(i)!!; LuaTokens.STRING }
            c.isDigit() -> { while (i < end && (buf[i].isLetterOrDigit() || buf[i] == '.')) i++; LuaTokens.NUMBER }
            c.isLetter() || c == '_' -> {
                while (i < end && (buf[i].isLetterOrDigit() || buf[i] == '_')) i++
                if (buf.subSequence(start, i).toString() in KEYWORDS) LuaTokens.KEYWORD else LuaTokens.IDENT
            }
            else -> { i++; LuaTokens.OTHER }
        }
        tokEnd = i
    }

    /** End offset of a `[[...]]` / `[==[...]==]` block starting at [from], or null if none starts there. */
    private fun longBracketEnd(from: Int): Int? {
        if (from >= end || buf[from] != '[') return null
        var i = from + 1
        var level = 0
        while (i < end && buf[i] == '=') { level++; i++ }
        if (i >= end || buf[i] != '[') return null
        val close = "]" + "=".repeat(level) + "]"
        val idx = buf.indexOf(close, i + 1)
        return if (idx < 0 || idx >= end) end else idx + close.length
    }
}

class LuaSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = LuaLexer()

    override fun getTokenHighlights(type: IElementType): Array<TextAttributesKey> = when (type) {
        LuaTokens.KEYWORD -> arrayOf(C.KEYWORD)
        LuaTokens.COMMENT -> arrayOf(C.LINE_COMMENT)
        LuaTokens.STRING -> arrayOf(C.STRING)
        LuaTokens.NUMBER -> arrayOf(C.NUMBER)
        else -> emptyArray()
    }
}

class LuaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = LuaSyntaxHighlighter()
}
