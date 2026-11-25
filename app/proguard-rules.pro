# JLatexMath finds its Cyrillic and Greek alphabets and some atoms by class name (Class.forName),
# so R8 must keep the package whole or release builds lose letters inside \text{}.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**
