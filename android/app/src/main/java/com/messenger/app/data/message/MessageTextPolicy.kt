package com.messenger.app.data.message

/** Own free-text policy. Unicode16 property DATA is pinned so JVM/Swift versions
 * cannot disagree. Data copyright2024 Unicode Inc., https://www.unicode.org/license.txt.
 * No normalization is performed on an already queued/frozen transport retry. */
internal class MessageTextPolicyException : IllegalArgumentException("Message text is invalid or exceeds the client resource budget")

internal object MessageTextPolicy {
    data class Limits(val bytes:Int,val scalars:Int,val breaks:Int,val marks:Int)
    val outgoingLimits=Limits(64*1024,32768,512,64)
    val captionLimits=Limits(16*1024,4096,512,64)
    val receivedLimits=Limits(256*1024,131072,4096,256)
    // Reactions are compact labels, not message bodies. No payload rewriting.
    private val reactionLimits=Limits(256,64,0,64)
    private val richLimits=Limits(32*1024,16384,512,64)
    const val maxPlaintextBytes=1024*1024
    const val maxJsonDepth=64
    enum class PresentationKind { TEXT, EMPTY, INVALID }
    data class PresentationResult(val kind:PresentationKind,val text:String,val truncated:Boolean)
    private data class Scan(val valid:Boolean,val visible:Boolean,val end:Int=0,val invalidScalar:Boolean=false)

    fun outgoing(raw:String):String = normalize(raw,outgoingLimits,false)!!
    fun caption(raw:String?):String? = raw?.let{normalize(it,captionLimits,true)}
    private fun normalize(raw:String,limits:Limits,optional:Boolean):String? {
        if(!scan(raw,receivedLimits).valid)throw MessageTextPolicyException()
        val value=trimEdges(raw)
        if(value.isEmpty() && optional && raw.codePoints().allMatch{whiteSpace(it)})return null
        val checked=scan(value,limits)
        if(!checked.valid || !checked.visible)throw MessageTextPolicyException()
        return value
    }
    /** Validate existing bytes without trimming/re-encoding an idempotency payload. */
    fun retry(raw:String?,optional:Boolean=false) {
        if(raw==null && optional)return
        val checked=scan(raw.orEmpty(),receivedLimits)
        if(!checked.valid || (!optional && !checked.visible))throw MessageTextPolicyException()
    }
    /** Incoming compatibility budget; preserves every accepted scalar/CRLF/tab. */
    fun received(raw:String?) {if(raw!=null && !scan(raw,receivedLimits).valid)throw MessageTextPolicyException()}
    fun receivedReaction(raw:String?) {if(raw!=null && !scan(raw,reactionLimits).valid)throw MessageTextPolicyException()}
    /** Derived projection also protects old/imported rows without deleting history. */
    fun isDisplayableReaction(raw:String?):Boolean {
        if(raw==null)return false
        val checked=scan(raw,reactionLimits)
        return checked.valid && checked.visible
    }
    /** Snapshot derivation is bounded before any prefix/grapheme operation. */
    fun replyPreview(raw:String):String {
        val value=presentation(raw,true)
        return if(value.codePointCount(0,value.length)<=140)value else value.substring(0,value.offsetByCodePoints(0,140))
    }
    fun allowsRichParsing(raw:String):Boolean=scan(raw,richLimits).valid
    private fun displayLimits(inline:Boolean)=if(inline)Limits(2048,512,4,64) else Limits(16*1024,8192,256,64)
    const val emptyPresentation="[Empty message]"
    const val invalidPresentation="[Unsupported message]"
    fun isPresentationBounded(raw:String):Boolean = presentationResult(raw).truncated
    fun presentation(source:String,inline:Boolean=false):String = presentationResult(source,inline).text
    fun presentationResult(source:String,inline:Boolean=false):PresentationResult {
        val raw=trimEdges(source)
        val limits=displayLimits(inline)
        val checked=scan(raw,limits)
        if(checked.invalidScalar)return PresentationResult(PresentationKind.INVALID,invalidPresentation,true)
        if(checked.valid)return if(checked.visible)PresentationResult(PresentationKind.TEXT,raw,false)
            else PresentationResult(PresentationKind.EMPTY,emptyPresentation,false)
        val prefix=scan(raw,limits.copy(bytes=limits.bytes-4,scalars=limits.scalars-2,breaks=limits.breaks-1),true).end
        // End at preceding whitespace so no partial emoji/combining word is
        // emitted. The original remains stored and the explicit full-text
        // viewer/copy action receives it, never this initial presentation.
        return PresentationResult(PresentationKind.TEXT,if(prefix>0)raw.substring(0,prefix)+"\n…" else "…",true)
    }

    /** Preflight only: linear bytes, quoted strings and escaped quotes/backslashes.
     * The real JSON decoder still decides syntax. Ordinary body punctuation is
     * inside a JSON string and does not contribute to structural depth. */
    private fun asciiWhitespace(value:Byte):Boolean = value==9.toByte() || value==10.toByte() || value==13.toByte() || value==32.toByte()
    fun allowsPlaintext(raw:ByteArray):Boolean {
        if(raw.size>maxPlaintextBytes)return false
        var start=0
        while(start<raw.size && asciiWhitespace(raw[start]))start++
        if(start+2<raw.size && raw[start]==0xEF.toByte() && raw[start+1]==0xBB.toByte() && raw[start+2]==0xBF.toByte())start+=3
        while(start<raw.size && asciiWhitespace(raw[start]))start++
        if(start==raw.size || (raw[start].toInt()!=123 && raw[start].toInt()!=91))return true
        var quoted=false;var escaped=false;var depth=0
        for(byte in raw) {
            val value=byte.toInt() and 255
            if(quoted) {
                if(escaped)escaped=false
                else if(value==92)escaped=true
                else if(value==34)quoted=false
            } else when(value) {
                34->quoted=true
                123,91->{depth++;if(depth>maxJsonDepth)return false}
                125,93->depth--
            }
        }
        return true
    }
    /** Only freshly decrypted packets use strict UTF-8; stored legacy decode stays compatible. */
    fun requireFreshPlaintext(raw:ByteArray) {
        if(!allowsPlaintext(raw) || !strictUtf8(raw))throw MessageTextPolicyException()
    }
    private fun strictUtf8(raw:ByteArray):Boolean {
        var index=0
        while(index<raw.size) {
            val lead=raw[index].toInt() and 255;index++
            if(lead<0x80)continue
            val count:Int;val low:Int;val high:Int
            when(lead) {
                in 0xC2..0xDF->{count=1;low=0x80;high=0xBF}
                0xE0->{count=2;low=0xA0;high=0xBF}
                in 0xE1..0xEC,in 0xEE..0xEF->{count=2;low=0x80;high=0xBF}
                0xED->{count=2;low=0x80;high=0x9F}
                0xF0->{count=3;low=0x90;high=0xBF}
                in 0xF1..0xF3->{count=3;low=0x80;high=0xBF}
                0xF4->{count=3;low=0x80;high=0x8F}
                else->return false
            }
            if(index+count>raw.size || (raw[index].toInt() and 255) !in low..high)return false
            for(offset in 1 until count)if((raw[index+offset].toInt() and 255) !in 0x80..0xBF)return false
            index+=count
        }
        return true
    }
    /** Remove whitespace at the edges, including ignorable wrappers stranded
     * outside it. Adjacent joiners/variation/bidi marks stay with actual text. */
    private fun trimEdges(raw:String):String {
        var first=0;var last=raw.length;var index=0
        while(index<last) {
            val value=raw.codePointAt(index)
            if(!whiteSpace(value) && !contains(defaultIgnorable,value))break
            index+=Character.charCount(value)
            if(whiteSpace(value))first=index
        }
        index=last
        while(index>first) {
            val value=raw.codePointBefore(index)
            if(!whiteSpace(value) && !contains(defaultIgnorable,value))break
            index-=Character.charCount(value)
            if(whiteSpace(value))last=index
        }
        while(first<last && edgePadding(raw.codePointAt(first)))first+=Character.charCount(raw.codePointAt(first))
        while(last>first && edgePadding(raw.codePointBefore(last)))last-=Character.charCount(raw.codePointBefore(last))
        return raw.substring(first,last)
    }
    private fun scan(raw:String,limits:Limits,collectPrefix:Boolean=false):Scan {
        if(!collectPrefix && raw.length>limits.scalars*2)return Scan(false,false)
        var index=0;var bytes=0;var scalars=0;var breaks=0;var run=0;var previousCR=false;var visible=false;var lastBoundary=0
        while(index<raw.length) {
            val value=raw.codePointAt(index)
            if(value==0 || value in 0xD800..0xDFFF)return Scan(false,false,lastBoundary,true)
            if(whiteSpace(value))lastBoundary=index
            index+=Character.charCount(value);scalars++
            bytes+=when{value<=0x7F->1;value<=0x7FF->2;value<=0xFFFF->3;else->4}
            if(value==13 || value==11 || value==12 || value==0x85 || value==0x2028 || value==0x2029 || (value==10 && !previousCR))breaks++
            previousCR=value==13
            val ignored=contains(defaultIgnorable,value)
            run=if(ignored || contains(combiningMarks,value))run+1 else 0
            if(!whiteSpace(value) && !ignored && value !in 0..31 && value !in 0x7F..0x9F)visible=true
            if(bytes>limits.bytes || scalars>limits.scalars || breaks>limits.breaks || run>limits.marks)return Scan(false,false,lastBoundary)
        }
        return Scan(true,visible)
    }
    private fun whiteSpace(value:Int):Boolean = value in 9..13 || value==32 || value==0x85 || value==0xA0 || value==0x1680 || value in 0x2000..0x200A || value==0x2028 || value==0x2029 || value==0x202F || value==0x205F || value==0x3000
    private fun edgePadding(value:Int):Boolean=whiteSpace(value) || value==0x200B || value==0x2060 || value==0xFEFF
    private fun contains(ranges:IntArray,value:Int):Boolean {
        var low=0;var high=ranges.size/2-1
        while(low<=high) {val mid=(low+high)/2;when{value<ranges[mid*2]->high=mid-1;value>ranges[mid*2+1]->low=mid+1;else->return true}}
        return false
    }
    private val combiningMarks=intArrayOf(
        0x300, 0x36F, 0x483, 0x489, 0x591, 0x5BD, 0x5BF, 0x5BF, 0x5C1, 0x5C2, 0x5C4, 0x5C5,
        0x5C7, 0x5C7, 0x610, 0x61A, 0x64B, 0x65F, 0x670, 0x670, 0x6D6, 0x6DC, 0x6DF, 0x6E4,
        0x6E7, 0x6E8, 0x6EA, 0x6ED, 0x711, 0x711, 0x730, 0x74A, 0x7A6, 0x7B0, 0x7EB, 0x7F3,
        0x7FD, 0x7FD, 0x816, 0x819, 0x81B, 0x823, 0x825, 0x827, 0x829, 0x82D, 0x859, 0x85B,
        0x897, 0x89F, 0x8CA, 0x8E1, 0x8E3, 0x903, 0x93A, 0x93C, 0x93E, 0x94F, 0x951, 0x957,
        0x962, 0x963, 0x981, 0x983, 0x9BC, 0x9BC, 0x9BE, 0x9C4, 0x9C7, 0x9C8, 0x9CB, 0x9CD,
        0x9D7, 0x9D7, 0x9E2, 0x9E3, 0x9FE, 0x9FE, 0xA01, 0xA03, 0xA3C, 0xA3C, 0xA3E, 0xA42,
        0xA47, 0xA48, 0xA4B, 0xA4D, 0xA51, 0xA51, 0xA70, 0xA71, 0xA75, 0xA75, 0xA81, 0xA83,
        0xABC, 0xABC, 0xABE, 0xAC5, 0xAC7, 0xAC9, 0xACB, 0xACD, 0xAE2, 0xAE3, 0xAFA, 0xAFF,
        0xB01, 0xB03, 0xB3C, 0xB3C, 0xB3E, 0xB44, 0xB47, 0xB48, 0xB4B, 0xB4D, 0xB55, 0xB57,
        0xB62, 0xB63, 0xB82, 0xB82, 0xBBE, 0xBC2, 0xBC6, 0xBC8, 0xBCA, 0xBCD, 0xBD7, 0xBD7,
        0xC00, 0xC04, 0xC3C, 0xC3C, 0xC3E, 0xC44, 0xC46, 0xC48, 0xC4A, 0xC4D, 0xC55, 0xC56,
        0xC62, 0xC63, 0xC81, 0xC83, 0xCBC, 0xCBC, 0xCBE, 0xCC4, 0xCC6, 0xCC8, 0xCCA, 0xCCD,
        0xCD5, 0xCD6, 0xCE2, 0xCE3, 0xCF3, 0xCF3, 0xD00, 0xD03, 0xD3B, 0xD3C, 0xD3E, 0xD44,
        0xD46, 0xD48, 0xD4A, 0xD4D, 0xD57, 0xD57, 0xD62, 0xD63, 0xD81, 0xD83, 0xDCA, 0xDCA,
        0xDCF, 0xDD4, 0xDD6, 0xDD6, 0xDD8, 0xDDF, 0xDF2, 0xDF3, 0xE31, 0xE31, 0xE34, 0xE3A,
        0xE47, 0xE4E, 0xEB1, 0xEB1, 0xEB4, 0xEBC, 0xEC8, 0xECE, 0xF18, 0xF19, 0xF35, 0xF35,
        0xF37, 0xF37, 0xF39, 0xF39, 0xF3E, 0xF3F, 0xF71, 0xF84, 0xF86, 0xF87, 0xF8D, 0xF97,
        0xF99, 0xFBC, 0xFC6, 0xFC6, 0x102B, 0x103E, 0x1056, 0x1059, 0x105E, 0x1060, 0x1062, 0x1064,
        0x1067, 0x106D, 0x1071, 0x1074, 0x1082, 0x108D, 0x108F, 0x108F, 0x109A, 0x109D, 0x135D, 0x135F,
        0x1712, 0x1715, 0x1732, 0x1734, 0x1752, 0x1753, 0x1772, 0x1773, 0x17B4, 0x17D3, 0x17DD, 0x17DD,
        0x180B, 0x180D, 0x180F, 0x180F, 0x1885, 0x1886, 0x18A9, 0x18A9, 0x1920, 0x192B, 0x1930, 0x193B,
        0x1A17, 0x1A1B, 0x1A55, 0x1A5E, 0x1A60, 0x1A7C, 0x1A7F, 0x1A7F, 0x1AB0, 0x1ACE, 0x1B00, 0x1B04,
        0x1B34, 0x1B44, 0x1B6B, 0x1B73, 0x1B80, 0x1B82, 0x1BA1, 0x1BAD, 0x1BE6, 0x1BF3, 0x1C24, 0x1C37,
        0x1CD0, 0x1CD2, 0x1CD4, 0x1CE8, 0x1CED, 0x1CED, 0x1CF4, 0x1CF4, 0x1CF7, 0x1CF9, 0x1DC0, 0x1DFF,
        0x20D0, 0x20F0, 0x2CEF, 0x2CF1, 0x2D7F, 0x2D7F, 0x2DE0, 0x2DFF, 0x302A, 0x302F, 0x3099, 0x309A,
        0xA66F, 0xA672, 0xA674, 0xA67D, 0xA69E, 0xA69F, 0xA6F0, 0xA6F1, 0xA802, 0xA802, 0xA806, 0xA806,
        0xA80B, 0xA80B, 0xA823, 0xA827, 0xA82C, 0xA82C, 0xA880, 0xA881, 0xA8B4, 0xA8C5, 0xA8E0, 0xA8F1,
        0xA8FF, 0xA8FF, 0xA926, 0xA92D, 0xA947, 0xA953, 0xA980, 0xA983, 0xA9B3, 0xA9C0, 0xA9E5, 0xA9E5,
        0xAA29, 0xAA36, 0xAA43, 0xAA43, 0xAA4C, 0xAA4D, 0xAA7B, 0xAA7D, 0xAAB0, 0xAAB0, 0xAAB2, 0xAAB4,
        0xAAB7, 0xAAB8, 0xAABE, 0xAABF, 0xAAC1, 0xAAC1, 0xAAEB, 0xAAEF, 0xAAF5, 0xAAF6, 0xABE3, 0xABEA,
        0xABEC, 0xABED, 0xFB1E, 0xFB1E, 0xFE00, 0xFE0F, 0xFE20, 0xFE2F, 0x101FD, 0x101FD, 0x102E0, 0x102E0,
        0x10376, 0x1037A, 0x10A01, 0x10A03, 0x10A05, 0x10A06, 0x10A0C, 0x10A0F, 0x10A38, 0x10A3A, 0x10A3F, 0x10A3F,
        0x10AE5, 0x10AE6, 0x10D24, 0x10D27, 0x10D69, 0x10D6D, 0x10EAB, 0x10EAC, 0x10EFC, 0x10EFF, 0x10F46, 0x10F50,
        0x10F82, 0x10F85, 0x11000, 0x11002, 0x11038, 0x11046, 0x11070, 0x11070, 0x11073, 0x11074, 0x1107F, 0x11082,
        0x110B0, 0x110BA, 0x110C2, 0x110C2, 0x11100, 0x11102, 0x11127, 0x11134, 0x11145, 0x11146, 0x11173, 0x11173,
        0x11180, 0x11182, 0x111B3, 0x111C0, 0x111C9, 0x111CC, 0x111CE, 0x111CF, 0x1122C, 0x11237, 0x1123E, 0x1123E,
        0x11241, 0x11241, 0x112DF, 0x112EA, 0x11300, 0x11303, 0x1133B, 0x1133C, 0x1133E, 0x11344, 0x11347, 0x11348,
        0x1134B, 0x1134D, 0x11357, 0x11357, 0x11362, 0x11363, 0x11366, 0x1136C, 0x11370, 0x11374, 0x113B8, 0x113C0,
        0x113C2, 0x113C2, 0x113C5, 0x113C5, 0x113C7, 0x113CA, 0x113CC, 0x113D0, 0x113D2, 0x113D2, 0x113E1, 0x113E2,
        0x11435, 0x11446, 0x1145E, 0x1145E, 0x114B0, 0x114C3, 0x115AF, 0x115B5, 0x115B8, 0x115C0, 0x115DC, 0x115DD,
        0x11630, 0x11640, 0x116AB, 0x116B7, 0x1171D, 0x1172B, 0x1182C, 0x1183A, 0x11930, 0x11935, 0x11937, 0x11938,
        0x1193B, 0x1193E, 0x11940, 0x11940, 0x11942, 0x11943, 0x119D1, 0x119D7, 0x119DA, 0x119E0, 0x119E4, 0x119E4,
        0x11A01, 0x11A0A, 0x11A33, 0x11A39, 0x11A3B, 0x11A3E, 0x11A47, 0x11A47, 0x11A51, 0x11A5B, 0x11A8A, 0x11A99,
        0x11C2F, 0x11C36, 0x11C38, 0x11C3F, 0x11C92, 0x11CA7, 0x11CA9, 0x11CB6, 0x11D31, 0x11D36, 0x11D3A, 0x11D3A,
        0x11D3C, 0x11D3D, 0x11D3F, 0x11D45, 0x11D47, 0x11D47, 0x11D8A, 0x11D8E, 0x11D90, 0x11D91, 0x11D93, 0x11D97,
        0x11EF3, 0x11EF6, 0x11F00, 0x11F01, 0x11F03, 0x11F03, 0x11F34, 0x11F3A, 0x11F3E, 0x11F42, 0x11F5A, 0x11F5A,
        0x13440, 0x13440, 0x13447, 0x13455, 0x1611E, 0x1612F, 0x16AF0, 0x16AF4, 0x16B30, 0x16B36, 0x16F4F, 0x16F4F,
        0x16F51, 0x16F87, 0x16F8F, 0x16F92, 0x16FE4, 0x16FE4, 0x16FF0, 0x16FF1, 0x1BC9D, 0x1BC9E, 0x1CF00, 0x1CF2D,
        0x1CF30, 0x1CF46, 0x1D165, 0x1D169, 0x1D16D, 0x1D172, 0x1D17B, 0x1D182, 0x1D185, 0x1D18B, 0x1D1AA, 0x1D1AD,
        0x1D242, 0x1D244, 0x1DA00, 0x1DA36, 0x1DA3B, 0x1DA6C, 0x1DA75, 0x1DA75, 0x1DA84, 0x1DA84, 0x1DA9B, 0x1DA9F,
        0x1DAA1, 0x1DAAF, 0x1E000, 0x1E006, 0x1E008, 0x1E018, 0x1E01B, 0x1E021, 0x1E023, 0x1E024, 0x1E026, 0x1E02A,
        0x1E08F, 0x1E08F, 0x1E130, 0x1E136, 0x1E2AE, 0x1E2AE, 0x1E2EC, 0x1E2EF, 0x1E4EC, 0x1E4EF, 0x1E5EE, 0x1E5EF,
        0x1E8D0, 0x1E8D6, 0x1E944, 0x1E94A, 0xE0100, 0xE01EF,
    )
    private val defaultIgnorable=intArrayOf(
        0xAD, 0xAD, 0x34F, 0x34F, 0x61C, 0x61C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180F,
        0x200B, 0x200F, 0x202A, 0x202E, 0x2060, 0x206F, 0x3164, 0x3164, 0xFE00, 0xFE0F, 0xFEFF, 0xFEFF,
        0xFFA0, 0xFFA0, 0xFFF0, 0xFFF8, 0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0xE0000, 0xE0FFF,
    )
}
