; Madoyomi's hooks into Tauri's NSIS installer (Phase 7 Stage 2), set in tauri.conf.json
; (bundle.windows.nsis.installerHooks). Tauri inserts each macro into its own installer.nsi.
;
; Tauri's uninstaller has a "Delete the application data" box. On its own it removes only
; %APPDATA%\com.madoyomi.desktop and %LOCALAPPDATA%\com.madoyomi.desktop (the WebView's storage),
; not Madoyomi's data dir, where the backend keeps the library, settings, extensions, cache, logs
; and the downloads. These hooks add:
;   - `uninstall.exe /S /REMOVEDATA`: a silent uninstall that ticks the box;
;   - when the box is ticked (never during an update): the data dir the app uses, removed as a
;     whole: %MADOYOMI_DATA_DIR% if set (an absolute path), else %APPDATA%\Madoyomi. Only a folder
;     with Madoyomi's server.conf in it is removed, so a mistyped variable can't empty another one.

!macro NSIS_HOOK_PREUNINSTALL
  ClearErrors
  ${GetOptions} $CMDLINE "/REMOVEDATA" $R9
  ${IfNot} ${Errors}
    StrCpy $DeleteAppDataCheckboxState 1
  ${EndIf}
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ${If} $DeleteAppDataCheckboxState = 1
  ${AndIf} $UpdateMode <> 1
    ReadEnvStr $R0 MADOYOMI_DATA_DIR
    ${If} $R0 == ""
      SetShellVarContext current
      StrCpy $R0 "$APPDATA\Madoyomi"
    ${EndIf}
    StrCpy $R1 $R0 1 1
    StrCpy $R2 $R0 2
    ${If} $R1 != ":"
    ${AndIf} $R2 != "\\"
      DetailPrint "Madoyomi: not removing the data dir $R0 (not an absolute path)"
    ${ElseIf} ${FileExists} "$R0\server.conf"
      DetailPrint "Madoyomi: removing the data dir $R0"
      RMDir /r "$R0"
    ${Else}
      DetailPrint "Madoyomi: not removing $R0 (no server.conf in it)"
    ${EndIf}
  ${EndIf}
!macroend
