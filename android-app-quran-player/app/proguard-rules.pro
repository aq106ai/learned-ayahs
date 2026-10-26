# Learned Ayahs Player — R8 rules for the release build.
#
# The app uses no reflection of its own, and its libraries (Compose, Media3, coroutines,
# navigation) ship their own consumer rules, so nothing needs keeping by hand. Enums are
# persisted by `.name`, which R8 leaves intact.

# Keep line numbers so the crash report the app shows, and Play's crash reports, can be
# retraced with the mapping file; hide the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
