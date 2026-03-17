<?xml version="1.0" encoding="UTF-8"?>
<!--
    xml-to-json.xsl
    Generic XML to JSON transformer.

    Works dynamically for ANY flat XML structure — field names are not
    hardcoded. Every child element of the root node is emitted as a
    JSON key-value pair.

    Type handling:
      Numeric values  → emitted without quotes  e.g. "amount": 2000
      String values   → emitted with quotes      e.g. "type": "Cars"

    Numeric detection:
      A value is treated as numeric if it satisfies:
        translate(value, '0123456789.', '') = ''
      i.e. the value contains only digits and at most one decimal point.

    Input:
      <message>
        <type>Cars</type>
        <data>BMW-X5-001</data>
        <amount>2000</amount>
        <currency>USD</currency>
      </message>

    Output:
      {
        "type": "Cars",
        "data": "BMW-X5-001",
        "amount": 2000,
        "currency": "USD"
      }

    Usage in Apache Camel:
      .to("xslt:xslt/xml-to-json.xsl")
-->
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

    <xsl:output method="text" encoding="UTF-8" indent="no"/>
    <xsl:strip-space elements="*"/>

    <!--
        ROOT TEMPLATE
        Matches the document root, opens the JSON object,
        iterates over all child elements of the root node,
        closes the object.
    -->
    <xsl:template match="/">
        <xsl:text>{&#10;</xsl:text>
        <xsl:for-each select="*/*">
            <xsl:text>  "</xsl:text>
            <xsl:value-of select="local-name()"/>
            <xsl:text>": </xsl:text>
            <xsl:call-template name="value">
                <xsl:with-param name="node" select="."/>
            </xsl:call-template>
            <xsl:if test="position() != last()">
                <xsl:text>,</xsl:text>
            </xsl:if>
            <xsl:text>&#10;</xsl:text>
        </xsl:for-each>
        <xsl:text>}</xsl:text>
    </xsl:template>

    <!--
        VALUE TEMPLATE
        Decides how to emit a node value:
          Has child elements → nested JSON object
          Numeric text       → emitted without quotes
          Boolean text       → emitted without quotes
          Empty text         → null
          String text        → emitted with quotes
    -->
    <xsl:template name="value">
        <xsl:param name="node"/>
        <xsl:choose>

            <!-- Nested object — node has child elements -->
            <xsl:when test="$node/*">
                <xsl:text>{&#10;</xsl:text>
                <xsl:for-each select="$node/*">
                    <xsl:text>    "</xsl:text>
                    <xsl:value-of select="local-name()"/>
                    <xsl:text>": </xsl:text>
                    <xsl:call-template name="value">
                        <xsl:with-param name="node" select="."/>
                    </xsl:call-template>
                    <xsl:if test="position() != last()">
                        <xsl:text>,</xsl:text>
                    </xsl:if>
                    <xsl:text>&#10;</xsl:text>
                </xsl:for-each>
                <xsl:text>  }</xsl:text>
            </xsl:when>

            <!-- Numeric — digits and optional single decimal point -->
            <!-- 2000, 99.99, 1500 → emitted without quotes        -->
            <xsl:when test="
                string-length($node) > 0 and
                translate($node, '0123456789.', '') = '' and
                string-length(translate($node, '.', '')) >= string-length($node) - 1">
                <xsl:value-of select="$node"/>
            </xsl:when>

            <!-- Boolean -->
            <xsl:when test="$node = 'true' or $node = 'false'">
                <xsl:value-of select="$node"/>
            </xsl:when>

            <!-- Null — empty element -->
            <xsl:when test="string-length($node) = 0">
                <xsl:text>null</xsl:text>
            </xsl:when>

            <!-- String — wrap in quotes and escape inner quotes -->
            <xsl:otherwise>
                <xsl:text>"</xsl:text>
                <xsl:call-template name="escape-string">
                    <xsl:with-param name="text" select="$node"/>
                </xsl:call-template>
                <xsl:text>"</xsl:text>
            </xsl:otherwise>

        </xsl:choose>
    </xsl:template>

    <!--
        ESCAPE-STRING TEMPLATE
        Escapes double quotes inside string values recursively.
        e.g.  He said "hello"  →  He said \"hello\"
    -->
    <xsl:template name="escape-string">
        <xsl:param name="text"/>
        <xsl:choose>
            <xsl:when test="contains($text, '&quot;')">
                <xsl:value-of select="substring-before($text, '&quot;')"/>
                <xsl:text>\"</xsl:text>
                <xsl:call-template name="escape-string">
                    <xsl:with-param name="text"
                                    select="substring-after($text, '&quot;')"/>
                </xsl:call-template>
            </xsl:when>
            <xsl:otherwise>
                <xsl:value-of select="$text"/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

</xsl:stylesheet>