' Localink launcher.
'
' Finds a Java runtime and starts the app with no console window.
' Android Studio's bundled JBR is checked first because if you can build the
' phone app, you already have it.

Option Explicit

Dim fso, shell, here, jar, candidates, i, javaw
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

here = fso.GetParentFolderName(WScript.ScriptFullName)
jar = fso.BuildPath(here, "localink.jar")

If Not fso.FileExists(jar) Then
    MsgBox "localink.jar was not found next to this launcher." & vbCrLf & vbCrLf & _
           "Expected: " & jar, vbCritical, "Localink"
    WScript.Quit 1
End If

candidates = Array( _
    fso.BuildPath(here, "runtime\bin\javaw.exe"), _
    shell.ExpandEnvironmentStrings("%JAVA_HOME%\bin\javaw.exe"), _
    shell.ExpandEnvironmentStrings("%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\javaw.exe"), _
    "C:\Program Files\Android\Android Studio\jbr\bin\javaw.exe", _
    "C:\Program Files\Java\jdk-21\bin\javaw.exe", _
    "C:\Program Files\Eclipse Adoptium\jdk-21\bin\javaw.exe", _
    "C:\Program Files\Microsoft\jdk-21\bin\javaw.exe" _
)

javaw = ""
For i = 0 To UBound(candidates)
    If Len(candidates(i)) > 0 And fso.FileExists(candidates(i)) Then
        javaw = candidates(i)
        Exit For
    End If
Next

' Last resort: whatever is on PATH.
If javaw = "" Then
    On Error Resume Next
    Dim probe
    probe = shell.Run("javaw.exe -version", 0, True)
    If Err.Number = 0 Then javaw = "javaw.exe"
    On Error GoTo 0
End If

If javaw = "" Then
    MsgBox "No Java runtime found." & vbCrLf & vbCrLf & _
           "Install Android Studio (which bundles one) or any JDK 17+, " & _
           "then run this again.", vbCritical, "Localink"
    WScript.Quit 1
End If

shell.Run """" & javaw & """ -jar """ & jar & """", 0, False
