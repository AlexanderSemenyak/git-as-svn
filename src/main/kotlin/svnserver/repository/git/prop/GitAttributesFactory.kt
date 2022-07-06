/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git.prop

import org.eclipse.jgit.attributes.Attribute
import org.eclipse.jgit.attributes.Attributes
import org.eclipse.jgit.attributes.AttributesNode
import org.eclipse.jgit.attributes.AttributesRule
import org.eclipse.jgit.errors.InvalidPatternException
import org.slf4j.Logger
import org.tmatesoft.svn.core.SVNProperty
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil
import svnserver.Loggers
import svnserver.repository.git.RepositoryFormat
import svnserver.repository.git.path.Wildcard
import java.io.IOException
import java.io.InputStream
import java.util.regex.PatternSyntaxException

/**
 * Factory for properties, generated by .gitattributes.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
class GitAttributesFactory : GitPropertyFactory {
    override val fileName: String
        get() {
            return ".gitattributes"
        }

    @Throws(IOException::class)
    override fun create(stream: InputStream, format: RepositoryFormat): Array<GitProperty> {
        val r = AttributesNode()
        r.parse(stream)
        val properties = ArrayList<GitProperty>()
        for (rule: AttributesRule in r.rules) {
            val wildcard: Wildcard
            try {
                wildcard = Wildcard(rule.pattern)
            } catch (e: InvalidPatternException) {
                log.warn("Found invalid git pattern: {}", rule.pattern)
                continue
            } catch (e: PatternSyntaxException) {
                log.warn("Found invalid git pattern: {}", rule.pattern)
                continue
            }
            val attrs = Attributes(*rule.attributes.map(::expandMacro).toTypedArray())

            val eolType: EolType = if (format < RepositoryFormat.V5_REMOVE_IMPLICIT_NATIVE_EOL) getEolTypeV4(attrs) else getEolTypeV5(attrs)
            processProperty(properties, wildcard, SVNProperty.MIME_TYPE, eolType.mimeType)
            processProperty(properties, wildcard, SVNProperty.EOL_STYLE, eolType.eolStyle)

            processProperty(properties, wildcard, SVNProperty.NEEDS_LOCK, getNeedsLock(attrs))
            val filter: String? = getFilter(attrs)
            if (filter != null) properties.add(GitFilterProperty.create(wildcard.matcher, filter))
        }
        return properties.toTypedArray()
    }

    enum class EolType(val mimeType: String?, val eolStyle: String?) {
        Autodetect(null, null),
        Binary(SVNFileUtil.BINARY_MIME_TYPE, ""),
        Native("", SVNProperty.EOL_STYLE_NATIVE),
        LF("", SVNProperty.EOL_STYLE_LF),
        CRLF("", SVNProperty.EOL_STYLE_CRLF);
    }

    private fun getNeedsLock(attrs: Attributes): String? {
        if (attrs.isSet("lockable")) return "*"
        return null
    }

    companion object {
        /**
         * TODO: more fully-functional macro expansion
         * @see org.eclipse.jgit.attributes.AttributesHandler.expandMacro
         * @see org.eclipse.jgit.attributes.AttributesHandler.BINARY_RULE_ATTRIBUTES
         */
        fun expandMacro(attr: Attribute): Attribute {
            return if (attr.key == "binary" && attr.state == Attribute.State.SET) {
                Attribute("text", Attribute.State.UNSET)
            } else {
                attr
            }
        }

        private val log: Logger = Loggers.git

        /**
         * @see org.eclipse.jgit.util.io.EolStreamTypeUtil.checkInStreamType
         * @see org.eclipse.jgit.util.io.EolStreamTypeUtil.checkOutStreamType
         */
        fun getEolTypeV5(attrs: Attributes): EolType {
            // "binary" or "-text" (which is included in the binary expansion)
            if (attrs.isUnset("text"))
                return EolType.Binary

            // old git system
            if (attrs.isSet("crlf")) {
                return EolType.Native
            } else if (attrs.isUnset("crlf")) {
                return EolType.Binary
            } else if ("input" == attrs.getValue("crlf")) {
                return EolType.LF
            }

            // new git system
            if ("auto" == attrs.getValue("text")) {
                return EolType.Autodetect
            }

            when (attrs.getValue("eol")) {
                "lf" -> return EolType.LF
                "crlf" -> return EolType.CRLF
            }

            if (attrs.isSet("text")) {
                return EolType.Native
            }

            return EolType.Autodetect
        }

        fun getEolTypeV4(attrs: Attributes): EolType {
            if (attrs.isUnset("text"))
                return EolType.Binary

            val eol: String? = attrs.getValue("eol")
            if (eol != null) {
                when (eol) {
                    "lf" -> return EolType.LF
                    "crlf" -> return EolType.CRLF
                }
            }

            if (attrs.isUnspecified("text"))
                return EolType.Autodetect

            return EolType.Native
        }

        private fun processProperty(properties: MutableList<GitProperty>, wildcard: Wildcard, property: String, value: String?) {
            if (value == null) {
                return
            }
            if (value.isNotEmpty()) {
                if (wildcard.isSvnCompatible) {
                    properties.add(GitAutoProperty.create(wildcard.matcher, property, value))
                }
                properties.add(GitFileProperty.create(wildcard.matcher, property, value))
            } else {
                properties.add(GitFileProperty.create(wildcard.matcher, property, null))
            }
        }

        private fun getFilter(attrs: Attributes): String? {
            return attrs.getValue("filter")
        }
    }
}
