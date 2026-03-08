<?xml version="1.0" encoding="UTF-8"?>

<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

    <xsl:output method="text"/>

    <xsl:template match="/order">

        {
        "orderId":"<xsl:value-of select='orderId'/>",
        "customer":"<xsl:value-of select='customer'/>",
        "amount":"<xsl:value-of select='amount'/>"
        }

    </xsl:template>

</xsl:stylesheet>