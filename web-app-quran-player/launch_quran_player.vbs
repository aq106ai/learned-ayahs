Option Explicit

' Launcher for the Learned Ayahs web player.
' - If the local server is already running, just open the player tab.
' - Otherwise start the server (silently, no console), wait for it, then open the tab.
' Invoked directly (double-click) or via the quranplayer: URL protocol from a browser bookmark.

Dim shell, fso, scriptDir, url, pythonw, logFile, i

Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
url = "http://127.0.0.1:8765/player.html"
logFile = fso.BuildPath(scriptDir, "launcher.log")

pythonw = FindPythonw()  ' portable: works on any machine with Python installed

Log "--- launch " & Now & " ---"

If ServerUp() Then
    Log "server already up; opening tab"
    shell.Run """" & url & """", 1, False
    WScript.Quit 0
End If

Log "server down; starting: " & pythonw
shell.CurrentDirectory = scriptDir
shell.Run """" & pythonw & """ ""play_learned_ayahs.py"" --serve-only --no-open", 0, False

For i = 1 To 30
    WScript.Sleep 300
    If ServerUp() Then
        Log "server came up after " & (i * 300) & " ms; opening tab"
        shell.Run """" & url & """", 1, False
        WScript.Quit 0
    End If
Next

Log "ERROR: server did not come up within timeout"
WScript.Quit 1


Function FindPythonw()
    ' Locate pythonw.exe on this machine. Checks the usual per-user and
    ' system-wide install roots, then falls back to bare "pythonw.exe" (PATH).
    Dim roots(2), i2, cand
    roots(0) = shell.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\Programs\Python"
    roots(1) = shell.ExpandEnvironmentStrings("%ProgramFiles%")
    roots(2) = shell.ExpandEnvironmentStrings("%ProgramFiles(x86)%")
    For i2 = 0 To 2
        cand = FindPythonwIn(roots(i2))
        If cand <> "" Then
            FindPythonw = cand
            Exit Function
        End If
    Next
    FindPythonw = "pythonw.exe"  ' rely on PATH
End Function


Function FindPythonwIn(dirPath)
    ' Return the newest pythonw.exe found directly inside a Python* subfolder.
    FindPythonwIn = ""
    On Error Resume Next
    If Not fso.FolderExists(dirPath) Then Exit Function
    Dim sf, cand
    For Each sf In fso.GetFolder(dirPath).SubFolders
        cand = fso.BuildPath(sf.Path, "pythonw.exe")
        If fso.FileExists(cand) Then FindPythonwIn = cand
    Next
    On Error Goto 0
End Function


Function ServerUp()
    ' Cache-buster query defeats WinHttp response caching (SimpleHTTPRequestHandler
    ' strips the query string, so player.html is still served correctly).
    Dim http, probe
    probe = url & "?_hc=" & CStr(Timer) & "_" & CStr(Int(Rnd * 100000))
    ServerUp = False
    On Error Resume Next
    Set http = CreateObject("WinHttp.WinHttpRequest.5.1")
    http.SetTimeouts 800, 800, 800, 800
    http.Open "GET", probe, False
    http.SetRequestHeader "Cache-Control", "no-cache"
    http.Send
    If Err.Number = 0 Then
        If http.Status = 200 Then ServerUp = True
    End If
    Err.Clear
    On Error Goto 0
End Function


Sub Log(msg)
    Dim ts
    On Error Resume Next
    Set ts = fso.OpenTextFile(logFile, 8, True)  ' 8 = append, create if missing
    ts.WriteLine msg
    ts.Close
    On Error Goto 0
End Sub
