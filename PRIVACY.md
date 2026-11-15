# MediaDroyd privacy policy

MediaDroyd collects nothing about you. It has no account, no ads, no analytics, no crash reporting and no server of
its own.

## What stays on your device

Everything the app remembers — the folders and sources you added, watched marks, resume points, settings and the
pictures it keeps for your library — is stored on your device only. Passwords of network sources are kept in the
platform's own secret store (Android Keystore, Windows DPAPI, the Linux secret service — where Linux has none, a
file in the app's folder only your user can read). A backup you make in Settings goes only into the file you choose.

## What goes over the network

- **Your own sources.** When you add a network share (SMB, NFS, FTP, SFTP, WebDAV) or a cloud folder, the app connects
  to it to read your library and play your videos, with the credentials you entered. On your local network it looks
  for SMB servers (mDNS) to offer them to you.
- **Neural network models, when you pick one.** Some picture options (DAIN, XVFI, FLAVR, CAIN, RealSR, further
  Real-CUGAN and waifu2x models, Real-ESRGAN on the desktop) are not carried in the app. Picking one downloads its
  files once from the [model repository on Hugging Face](https://huggingface.co/Hawkynt/mediadroid-models), from
  GitHub (the ncnn ports' repositories and the Real-ESRGAN releases). Those services see your IP address as with any
  download; nothing else is sent.

Nothing else leaves your device.

## Permissions (Android)

- **All files access** (GitHub, F-Droid and IzzyOnDroid builds only): to read your library's folders, `.nfo` files and
  cover art by their paths. The Google Play build does not ask for it and reads only the folders you pick.
- **Notifications and foreground service**: the playback notification, and playing on a second screen while the app is
  in the background.
- **Network**: your network sources and the model downloads above.

## Contact

Questions: open an issue at <https://github.com/Hawkynt/MediaDroyd/issues>.
