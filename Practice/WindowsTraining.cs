using System.Reflection;
using System.Runtime.InteropServices;
using System.Speech.Recognition;

namespace ScriptureMemory.Practice;

/// <summary>
/// Access to SAPI's recognizer training (the same mechanism the Windows speech training wizard uses).
/// System.Speech doesn't expose it, so we reach the underlying SAPI recognizer and call
/// ISpRecognizer2::SetTrainingState. While training is on, the Windows recognizer collects the audio of
/// what it recognizes; turning training off with adapt = true updates the user's Windows voice profile.
/// Every step is best-effort: if anything isn't available, calibration still works in-app.
/// </summary>
internal static class WindowsTraining
{
    public static bool TrySetTrainingState(SpeechRecognitionEngine engine, bool training, bool adapt)
    {
        try
        {
            if (GetSapiRecognizer(engine) is not ISpRecognizer2 recognizer) return false;
            recognizer.SetTrainingState(training, adapt);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    // SpeechRecognitionEngine._sapiRecognizer (SapiRecognizer)._proxy (SapiProxy)._recognizer (SAPI COM object)
    private static object? GetSapiRecognizer(SpeechRecognitionEngine engine)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var sapi = typeof(SpeechRecognitionEngine).GetField("_sapiRecognizer", flags)?.GetValue(engine);
        var proxy = sapi?.GetType().GetField("_proxy", flags)?.GetValue(sapi);
        if (proxy == null) return null;
        for (var t = proxy.GetType(); t != null; t = t.BaseType)
        {
            var field = t.GetField("_recognizer", flags);
            if (field != null) return field.GetValue(proxy);
        }
        return null;
    }

    [ComImport, Guid("8FC6D974-C81E-4098-93C5-0147F61ED4D3"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ISpRecognizer2
    {
        void EmulateRecognitionEx(IntPtr pPhrase, uint dwCompareFlags);
        void SetTrainingState([MarshalAs(UnmanagedType.Bool)] bool fDoingTraining,
                              [MarshalAs(UnmanagedType.Bool)] bool fAdaptFromTrainingData);
        void ResetAcousticModelAdaptation();
    }
}
