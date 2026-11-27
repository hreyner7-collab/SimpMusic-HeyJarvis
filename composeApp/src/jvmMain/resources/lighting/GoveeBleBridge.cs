using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Web.Script.Serialization;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Foundation;
using Windows.Storage.Streams;

internal static class Program
{
    private static readonly Guid ServiceUuid = new Guid("00010203-0405-0607-0809-0a0b0c0d1910");
    private static readonly Guid WriteCharUuid = new Guid("00010203-0405-0607-0809-0a0b0c0d2b11");
    private static readonly string[] NameTokens = { "govee", "ihoment", "gbk", "_h6" };

    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    private static readonly ConcurrentDictionary<string, BluetoothLEDevice> Devices = new ConcurrentDictionary<string, BluetoothLEDevice>(StringComparer.OrdinalIgnoreCase);
    private static readonly ConcurrentDictionary<string, GattCharacteristic> WriteChars = new ConcurrentDictionary<string, GattCharacteristic>(StringComparer.OrdinalIgnoreCase);
    private static readonly HashSet<string> InitializedAddrs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

    private static void Main()
    {
        Console.InputEncoding = System.Text.Encoding.UTF8;
        Console.OutputEncoding = System.Text.Encoding.UTF8;

        string line;
        while ((line = Console.ReadLine()) != null)
        {
            line = line.Trim();
            if (line.Length == 0) continue;

            Dictionary<string, object> res;
            try
            {
                var req = Json.Deserialize<Dictionary<string, object>>(line);
                res = Handle(req);
            }
            catch (Exception ex)
            {
                res = new Dictionary<string, object>
                {
                    { "ok", false },
                    { "error", ex.Message }
                };
            }

            Console.Out.WriteLine(Json.Serialize(res));
            Console.Out.Flush();

            object quitVal;
            if (res.TryGetValue("quit", out quitVal) && quitVal is bool && (bool)quitVal)
            {
                break;
            }
        }
    }

    private static Dictionary<string, object> Handle(Dictionary<string, object> req)
    {
        var cmd = GetStr(req, "cmd");
        if (cmd == "scan")
        {
            double timeoutSec = GetDouble(req, "timeout", 4.0);
            return Scan(timeoutSec);
        }
        if (cmd == "power")
        {
            string addr = GetStr(req, "addr");
            bool on = GetBool(req, "on", true);
            WriteFrame(addr, MakeFrame(0x33, 0x01, new byte[] { on ? (byte)0x01 : (byte)0x00 }));
            if (on)
            {
                WriteFrame(addr, MakeFrame(0x33, 0x04, new byte[] { 0xFF }));
                InitializedAddrs.Add(addr);
            }
            else
            {
                InitializedAddrs.Remove(addr);
            }
            return Ok();
        }
        if (cmd == "color")
        {
            string addr = GetStr(req, "addr");
            byte r = (byte)Math.Max(0, Math.Min(255, GetInt(req, "r", 0)));
            byte g = (byte)Math.Max(0, Math.Min(255, GetInt(req, "g", 0)));
            byte b = (byte)Math.Max(0, Math.Min(255, GetInt(req, "b", 0)));
            if (!InitializedAddrs.Contains(addr))
            {
                WriteFrame(addr, MakeFrame(0x33, 0x04, new byte[] { 0xFF }));
                InitializedAddrs.Add(addr);
            }
            WriteFrame(addr, MakeFrame(0x33, 0x05, new byte[] { 0x02, r, g, b }));
            return Ok();
        }
        if (cmd == "blink")
        {
            string addr = GetStr(req, "addr");
            WriteFrame(addr, MakeFrame(0x33, 0x01, new byte[] { 0x01 }));
            WriteFrame(addr, MakeFrame(0x33, 0x04, new byte[] { 0xFF }));
            WriteFrame(addr, MakeFrame(0x33, 0x05, new byte[] { 0x02, 0xFF, 0x00, 0x00 }));
            Thread.Sleep(850);
            WriteFrame(addr, MakeFrame(0x33, 0x01, new byte[] { 0x00 }));
            InitializedAddrs.Remove(addr);
            return Ok();
        }
        if (cmd == "quit")
        {
            foreach (var dev in Devices.Values)
            {
                try { dev.Dispose(); } catch { }
            }
            Devices.Clear();
            WriteChars.Clear();
            return new Dictionary<string, object> { { "ok", true }, { "quit", true } };
        }
        return new Dictionary<string, object> { { "ok", false }, { "error", "Unknown cmd " + cmd } };
    }

    private static Dictionary<string, object> Scan(double timeoutSec)
    {
        var found = new ConcurrentDictionary<ulong, Dictionary<string, object>>();
        var watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active
        };
        TypedEventHandler<BluetoothLEAdvertisementWatcher, BluetoothLEAdvertisementReceivedEventArgs> handler = (sender, args) =>
        {
            string name = (args.Advertisement.LocalName ?? string.Empty).Trim();
            string lower = name.ToLowerInvariant();
            bool nameMatch = NameTokens.Any(t => lower.Contains(t));
            bool uuidMatch = args.Advertisement.ServiceUuids != null && args.Advertisement.ServiceUuids.Contains(ServiceUuid);
            if (!nameMatch && !uuidMatch) return;

            string mac = FormatMac(args.BluetoothAddress);
            found[args.BluetoothAddress] = new Dictionary<string, object>
            {
                { "name", name.Length > 0 ? name : "Govee " + mac },
                { "addr", mac },
                { "rssi", (int)args.RawSignalStrengthInDBm }
            };
        };

        watcher.Received += handler;
        try
        {
            watcher.Start();
            int ms = Math.Max(1500, Math.Min(8000, (int)(timeoutSec * 1000.0)));
            Thread.Sleep(ms);
            watcher.Stop();
        }
        catch (Exception ex)
        {
            string msg = ex.Message ?? "Bluetooth unavailable";
            if (msg.IndexOf("radio", StringComparison.OrdinalIgnoreCase) >= 0 ||
                msg.IndexOf("not ready", StringComparison.OrdinalIgnoreCase) >= 0 ||
                msg.IndexOf("disabled", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                return new Dictionary<string, object> { { "ok", false }, { "error", "Turn on Bluetooth in Windows Settings to find Govee lights" } };
            }
            return new Dictionary<string, object> { { "ok", false }, { "error", msg } };
        }
        finally
        {
            watcher.Received -= handler;
        }

        var devices = found.Values.OrderByDescending(d => (int)d["rssi"]).ToList();
        return new Dictionary<string, object>
        {
            { "ok", true },
            { "devices", devices }
        };
    }

    private static GattCharacteristic GetWriteChar(string addr)
    {
        GattCharacteristic cached;
        if (WriteChars.TryGetValue(addr, out cached)) return cached;

        ulong btAddr = ParseMac(addr);
        var dev = Devices.GetOrAdd(addr, _ =>
        {
            var d = AwaitOp(BluetoothLEDevice.FromBluetoothAddressAsync(btAddr), 8000);
            if (d == null) throw new Exception("Failed to connect to " + addr);
            return d;
        });

        var svcResult = AwaitOp(dev.GetGattServicesAsync(BluetoothCacheMode.Uncached), 8000);
        if (svcResult.Status != GattCommunicationStatus.Success)
        {
            throw new Exception("Govee GATT services failed (" + svcResult.Status + ")");
        }
        var svc = svcResult.Services.FirstOrDefault(s => s.Uuid == ServiceUuid);
        if (svc == null)
        {
            throw new Exception("Govee GATT service not found");
        }

        var charResult = AwaitOp(svc.GetCharacteristicsAsync(BluetoothCacheMode.Uncached), 8000);
        if (charResult.Status != GattCommunicationStatus.Success)
        {
            throw new Exception("Govee GATT characteristics failed (" + charResult.Status + ")");
        }
        var ch = charResult.Characteristics.FirstOrDefault(c => c.Uuid == WriteCharUuid);
        if (ch == null)
        {
            throw new Exception("Govee GATT characteristic not found");
        }
        WriteChars[addr] = ch;
        return ch;
    }

    private static void WriteFrame(string addr, byte[] frame)
    {
        var ch = GetWriteChar(addr);
        var dw = new DataWriter();
        dw.WriteBytes(frame);
        IBuffer buf = dw.DetachBuffer();
        var status = AwaitOp(ch.WriteValueAsync(buf, GattWriteOption.WriteWithoutResponse), 4000);
        if (status != GattCommunicationStatus.Success)
        {
            status = AwaitOp(ch.WriteValueAsync(buf, GattWriteOption.WriteWithResponse), 4000);
            if (status != GattCommunicationStatus.Success)
            {
                GattCharacteristic removed;
                WriteChars.TryRemove(addr, out removed);
                throw new Exception("BLE write failed: " + status);
            }
        }
    }

    private static T AwaitOp<T>(IAsyncOperation<T> op, int timeoutMs)
    {
        using (var done = new ManualResetEventSlim(false))
        {
            op.Completed = (info, status) => done.Set();
            if (!done.Wait(timeoutMs)) throw new TimeoutException("Bluetooth operation timed out");
            return op.GetResults();
        }
    }

    private static byte[] MakeFrame(byte head, byte cmd, byte[] payload)
    {
        var b = new byte[20];
        b[0] = head;
        b[1] = cmd;
        int len = Math.Min(payload.Length, 17);
        for (int i = 0; i < len; i++) b[2 + i] = payload[i];
        byte xor = 0;
        for (int i = 0; i < 19; i++) xor ^= b[i];
        b[19] = xor;
        return b;
    }

    private static string FormatMac(ulong addr)
    {
        string hex = addr.ToString("X12");
        return string.Join(":", Enumerable.Range(0, 6).Select(i => hex.Substring(i * 2, 2)));
    }

    private static ulong ParseMac(string mac)
    {
        string clean = (mac ?? string.Empty).Replace(":", "").Replace("-", "").Trim();
        return ulong.Parse(clean, NumberStyles.HexNumber, CultureInfo.InvariantCulture);
    }

    private static Dictionary<string, object> Ok()
    {
        return new Dictionary<string, object> { { "ok", true } };
    }

    private static string GetStr(Dictionary<string, object> d, string key)
    {
        object v;
        return d != null && d.TryGetValue(key, out v) && v != null ? v.ToString() : string.Empty;
    }

    private static int GetInt(Dictionary<string, object> d, string key, int def)
    {
        object v;
        if (d != null && d.TryGetValue(key, out v) && v != null)
        {
            int parsed;
            if (int.TryParse(v.ToString(), out parsed)) return parsed;
        }
        return def;
    }

    private static double GetDouble(Dictionary<string, object> d, string key, double def)
    {
        object v;
        if (d != null && d.TryGetValue(key, out v) && v != null)
        {
            double parsed;
            if (double.TryParse(v.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out parsed)) return parsed;
        }
        return def;
    }

    private static bool GetBool(Dictionary<string, object> d, string key, bool def)
    {
        object v;
        if (d != null && d.TryGetValue(key, out v) && v != null)
        {
            bool parsed;
            if (bool.TryParse(v.ToString(), out parsed)) return parsed;
        }
        return def;
    }
}
