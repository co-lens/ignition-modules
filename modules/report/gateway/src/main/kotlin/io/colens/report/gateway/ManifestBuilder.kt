package io.colens.report.gateway

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext
import io.colens.report.common.Constants
import java.util.UUID

/**
 * Generates the Office add-in manifest for THIS gateway.
 *
 * The manifest has to carry absolute URLs to wherever the add-in is served from, so it cannot be a
 * static file — the gateway does not know its own public address at build time, and on a plant
 * network it is different for every install. It is built per request instead, from the address the
 * request itself arrived on.
 *
 * The XML manifest, not the unified JSON one. Two reasons, and the second is decisive: unified
 * manifests cannot be sideloaded from a network share, which is exactly how an add-in reaches
 * users on an isolated plant network; and their support for Excel custom functions on Mac and the
 * web still lags.
 */
object ManifestBuilder {

    /**
     * Stable per gateway, derived from the public address.
     *
     * Office treats the id as the add-in's identity: a value that changed between requests would
     * make every re-sideload install a DUPLICATE add-in rather than replace the previous one, and
     * the user would accumulate them with no way to tell which is current.
     */
    fun addinId(baseUrl: String): UUID = UUID.nameUUIDFromBytes("io.colens.report-ign@$baseUrl".toByteArray())

    /**
     * The address a client should use to reach this gateway.
     *
     * Honours `X-Forwarded-Proto` / `X-Forwarded-Host` before the request's own view, because a
     * gateway behind a reverse proxy sees the INTERNAL address and a manifest full of those is
     * useless to every client outside it. `-Dreport.publicUrl` overrides both, for the case where
     * neither is right.
     */
    fun baseUrl(ctx: RequestContext): String {
        System.getProperty("report.publicUrl")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it.trimEnd('/')
        }

        val request = ctx.request
        val proto = request.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()
            ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host")?.substringBefore(',')?.trim()
            ?: buildString {
                append(request.serverName)
                val port = request.serverPort
                val default = (proto == "https" && port == 443) || (proto == "http" && port == 80)
                if (!default) append(':').append(port)
            }

        return "$proto://$host"
    }

    /**
     * Office requires HTTPS for every add-in resource and simply refuses a manifest served over
     * plain HTTP — silently, differently on each platform, usually with nothing the user can act
     * on beyond "the add-in doesn't appear". Refusing to emit one here turns that into a message
     * at the moment somebody could still fix it.
     */
    fun isInsecure(baseUrl: String): Boolean = baseUrl.startsWith("http://")

    fun build(baseUrl: String, version: String): String {
        /*
         * No "/addin" segment. getMountedResourceFolder names the resource folder to serve, and
         * the platform serves its CONTENTS at /res/<alias>/ — the folder name itself is stripped.
         * Guessing otherwise gives a manifest whose every URL 404s, and Office reports that as the
         * add-in simply not appearing.
         */
        val app = "$baseUrl/res/${Constants.SHORT_MODULE_ID}"
        val id = addinId(baseUrl)

        /*
         * A plain four-part number, with a MAJOR OF AT LEAST 1.
         *
         * Two separate rules, both enforced by Office and neither obvious. It rejects any version
         * carrying a qualifier, so "0.1.0-SNAPSHOT" has to be trimmed; and it rejects anything
         * below 1.0 outright — "Manifest Version Too Low" — which every pre-1.0 module version
         * would otherwise hit. The floor is applied here rather than forcing the module itself to
         * pretend it is 1.0: this number is Office's notion of the add-in version, not the
         * module's, and the two need not agree.
         */
        val numeric = version.takeWhile { it.isDigit() || it == '.' }.trim('.').split('.')
        val parts = (numeric + listOf("0", "0", "0", "0")).take(4).toMutableList()
        if ((parts[0].toIntOrNull() ?: 0) < 1) parts[0] = "1"
        val manifestVersion = parts.joinToString(".")

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <OfficeApp xmlns="http://schemas.microsoft.com/office/appforoffice/1.1"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xmlns:bt="http://schemas.microsoft.com/office/officeappbasictypes/1.0"
                       xmlns:ov="http://schemas.microsoft.com/office/taskpaneappversionoverrides"
                       xsi:type="TaskPaneApp">
              <Id>$id</Id>
              <Version>$manifestVersion</Version>
              <ProviderName>co-lens</ProviderName>
              <DefaultLocale>en-US</DefaultLocale>
              <DisplayName DefaultValue="Ignition Report"/>
              <Description DefaultValue="Build Excel reports from Ignition historian data."/>
              <IconUrl DefaultValue="$app/icon-32.png"/>
              <HighResolutionIconUrl DefaultValue="$app/icon-80.png"/>
              <!-- Required by the schema, and element order here is fixed: SupportUrl must follow
                   the icon URLs and precede Hosts. It points at the gateway serving the add-in,
                   which for an on-premises install is genuinely where support starts. -->
              <SupportUrl DefaultValue="$baseUrl/data/${Constants.SHORT_MODULE_ID}/health"/>
              <Hosts>
                <Host Name="Workbook"/>
              </Hosts>
              <DefaultSettings>
                <SourceLocation DefaultValue="$app/taskpane.html"/>
              </DefaultSettings>
              <Permissions>ReadWriteDocument</Permissions>
              <VersionOverrides xmlns="http://schemas.microsoft.com/office/taskpaneappversionoverrides" xsi:type="VersionOverridesV1_0">
                <Hosts>
                  <Host xsi:type="Workbook">
                    <!-- NO <Runtimes> element, deliberately.
                         
                         It requests a SHARED runtime, which is a Windows feature: Excel on the web
                         cannot provide one, and declaring it there leaves Office.onReady pending
                         FOREVER. The pane loads, `typeof Office` is "object", and the handshake
                         simply never completes — with no error in the pane, the console or the
                         Office UI. That cost a debugging session, and the only reason it was found
                         is that the pane now reports its own startup state.
                         
                         Nothing here needs one. The API token travels between the task pane and
                         the custom-function runtime through OfficeRuntime.storage precisely
                         because the two are separate runtimes on Mac and the web regardless, so
                         the shared runtime would have bought nothing but this failure. -->
                    <AllFormFactors>
                      <ExtensionPoint xsi:type="CustomFunctions">
                        <Script><SourceLocation resid="Functions.Script"/></Script>
                        <Page><SourceLocation resid="Functions.Page"/></Page>
                        <Metadata><SourceLocation resid="Functions.Metadata"/></Metadata>
                        <!-- What makes the formula read =IGN.HISTORY rather than =CONTOSO.HISTORY. -->
                        <Namespace resid="Functions.Namespace"/>
                      </ExtensionPoint>
                    </AllFormFactors>
                    <DesktopFormFactor>
                      <ExtensionPoint xsi:type="PrimaryCommandSurface">
                        <OfficeTab id="TabHome">
                          <Group id="IgnitionReport.Group">
                            <Label resid="Group.Label"/>
                            <!-- A Group's children are ORDERED: Label, then Icon, then Controls.
                                 Omitting the Icon fails schema validation with "invalid child
                                 element 'Control' ... expected 'Icon'", which Excel reports to the
                                 user as nothing more than "Your add-in manifest is not valid". -->
                            <Icon>
                              <bt:Image size="16" resid="Icon.16"/>
                              <bt:Image size="32" resid="Icon.32"/>
                              <bt:Image size="80" resid="Icon.80"/>
                            </Icon>
                            <Control xsi:type="Button" id="IgnitionReport.Open">
                              <Label resid="Open.Label"/>
                              <Supertip>
                                <Title resid="Open.Label"/>
                                <Description resid="Open.Tip"/>
                              </Supertip>
                              <Icon>
                                <bt:Image size="16" resid="Icon.16"/>
                                <bt:Image size="32" resid="Icon.32"/>
                                <bt:Image size="80" resid="Icon.80"/>
                              </Icon>
                              <Action xsi:type="ShowTaskpane">
                                <TaskpaneId>ButtonId1</TaskpaneId>
                                <SourceLocation resid="Taskpane.Url"/>
                              </Action>
                            </Control>
                          </Group>
                        </OfficeTab>
                      </ExtensionPoint>
                    </DesktopFormFactor>
                  </Host>
                </Hosts>
                <Resources>
                  <bt:Images>
                    <bt:Image id="Icon.16" DefaultValue="$app/icon-16.png"/>
                    <bt:Image id="Icon.32" DefaultValue="$app/icon-32.png"/>
                    <bt:Image id="Icon.80" DefaultValue="$app/icon-80.png"/>
                  </bt:Images>
                  <bt:Urls>
                    <bt:Url id="Taskpane.Url" DefaultValue="$app/taskpane.html"/>
                    <bt:Url id="Functions.Script" DefaultValue="$app/functions.js"/>
                    <bt:Url id="Functions.Page" DefaultValue="$app/functions.html"/>
                    <bt:Url id="Functions.Metadata" DefaultValue="$app/functions.json"/>
                  </bt:Urls>
                  <bt:ShortStrings>
                    <bt:String id="Functions.Namespace" DefaultValue="IGN"/>
                    <bt:String id="Group.Label" DefaultValue="Ignition"/>
                    <bt:String id="Open.Label" DefaultValue="Ignition Report"/>
                  </bt:ShortStrings>
                  <bt:LongStrings>
                    <bt:String id="Open.Tip" DefaultValue="Build reports from Ignition historian data."/>
                  </bt:LongStrings>
                </Resources>
              </VersionOverrides>
            </OfficeApp>
        """.trimIndent()
    }
}
