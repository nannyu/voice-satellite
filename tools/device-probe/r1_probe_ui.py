#!/usr/bin/env python3
"""通过 ADB 操作 Voice Satellite #9 的界面并导出文本报告。

仅用于用户自己的 R1。不会安装软件、修改系统包、提权或上传数据。
run 会重新启动本诊断应用，清除上一轮未保存的界面报告；read 只读报告。
依赖电脑上的 Python 3.8+、adb，以及设备上的 uiautomator/input。
"""
import argparse
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "io.nannyu.voicesatellite.r1"
BUTTON = "Run automatic R1 Audio Probe"
SOURCES = ("MIC", "VOICE_RECOGNITION", "VOICE_COMMUNICATION")


class ProbeError(RuntimeError):
    pass


def parse_ui(text):
    """忽略工具附带文本，仅解析完整 hierarchy XML。"""
    start = text.find("<hierarchy")
    end = text.rfind("</hierarchy>")
    if start < 0 or end < start:
        raise ProbeError("未获得界面 XML：\n" + text[-1600:])
    xml = text[start:end + len("</hierarchy>")]
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as exc:
        raise ProbeError("界面 XML 格式错误：" + str(exc)) from exc
    return xml, root


def app_nodes(root):
    return [n for n in root.iter("node") if n.get("package") == PACKAGE]


def button_center(root):
    found = [n for n in app_nodes(root) if n.get("text") == BUTTON
             and n.get("class") == "android.widget.Button"
             and n.get("enabled") == "true" and n.get("clickable") == "true"]
    if len(found) != 1:
        labels = [n.get("text", "") for n in app_nodes(root) if n.get("text")]
        raise ProbeError("未找到唯一可用的 Probe 按钮，已停止，不会盲点坐标。\n"
                         + "应用界面文字：\n" + "\n".join(labels))
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", found[0].get("bounds", ""))
    if match is None:
        raise ProbeError("按钮 bounds 无效。")
    x1, y1, x2, y2 = map(int, match.groups())
    if x2 <= x1 or y2 <= y1:
        raise ProbeError("按钮没有可点击的显示区域。")
    return (x1 + x2) // 2, (y1 + y2) // 2


def read_report(root):
    reports = [n.get("text", "") for n in app_nodes(root)
               if n.get("text", "").startswith("R1 AUDIO PROBE")]
    if not reports:
        return None
    if len(reports) != 1:
        raise ProbeError("界面存在多个报告，不能确定结果。")
    report = reports[0]
    if "CANCELLED" in report:
        raise ProbeError("Probe 被取消，未取得完整报告。\n" + report)
    if not all("[" + s + "]" in report for s in SOURCES):
        raise ProbeError("报告缺少三路中的部分结果。\n" + report)
    return report


class Device:
    def __init__(self, serial, folder):
        self.serial = serial
        self.folder = folder
        self.last_xml = None

    def adb(self, args, timeout=20):
        try:
            result = subprocess.run(["adb", "-s", self.serial] + args,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    encoding="utf-8", errors="replace", timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            raise ProbeError("ADB 命令超时，未宣称测试完成：" + " ".join(args)) from exc
        if result.returncode:
            raise ProbeError("ADB 命令失败：\n" + result.stdout + result.stderr)
        return result.stdout

    def shell(self, command, timeout=20):
        # 老 adbd 可能不回传远端退出码，用标记验证。保留设备端 sh -c 的引号。
        marker = "__R1_RC_" + uuid.uuid4().hex + "__"
        # R1 3415 的精简 shell 没有 printf；用内建 echo 回传退出码。
        script = command + "; r1_rc=$?; echo; echo " + marker + "$r1_rc"
        text = self.adb(["shell", "sh -c " + shlex.quote(script)], timeout)
        match = re.search(re.escape(marker) + r"(\d+)\s*$", text)
        if match is None:
            raise ProbeError("远端 shell 未返回完成标记：\n" + text[-1600:])
        output = text[:match.start()].strip()
        if int(match.group(1)) != 0:
            raise ProbeError("远端命令失败：\n" + output)
        return output

    def launch(self):
        text = self.shell("am start -W -n " + PACKAGE + "/.MainActivity", 30)
        print(text, flush=True)
        if re.search(r"(?im)^\s*(Error|Exception)|Status:\s*(timeout|error)", text):
            raise ProbeError("Activity 启动未确认成功。")

    def snapshot(self, name):
        # 每次用全新文件名，不会把上一次 dump 冒充本次结果。
        remote = "/data/local/tmp/r1-probe-ui-" + uuid.uuid4().hex + ".xml"
        try:
            text = self.shell("uiautomator dump " + remote + "; cat " + remote, 20)
            xml, root = parse_ui(text)
            path = self.folder / (name + ".xml")
            path.write_text(xml, encoding="utf-8")
            self.last_xml = path
            if not app_nodes(root):
                raise ProbeError("当前界面不属于 Voice Satellite，已停止。XML：" + str(path))
            return root
        finally:
            try:
                self.shell("rm -f " + remote, 5)
            except ProbeError:
                pass

    def stop_app(self):
        # 仅停止本应用，防止旧版 Probe 读取卡住后仍占用麦克风。
        self.shell("am force-stop " + PACKAGE, 10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "run", "read"))
    parser.add_argument("--serial", default="192.168.1.17:5555")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent / "r1-probe-results"),
                        help="新建或复用本地报告目录（默认 tools/device-probe/r1-probe-results，已 gitignore）")
    parser.add_argument("--label", choices=("quiet", "speech"), default="speech")
    args = parser.parse_args()
    if shutil.which("adb") is None:
        print("找不到 adb，请在此前已能执行 adb 的电脑环境运行。", file=sys.stderr)
        return 1
    stamp = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    folder = Path(args.out).expanduser().resolve() / (args.label + "-" + stamp)
    folder.mkdir(parents=True, exist_ok=False)
    device = Device(args.serial, folder)
    tapped = False
    finished = False
    try:
        if device.adb(["get-state"]).strip() != "device":
            raise ProbeError("设备未处于 device 状态。")
        if args.action == "run":
            device.stop_app()  # 先清除旧界面，防止把上一次报告当成这次结果。
        if args.action != "read":
            device.launch()
        root = device.snapshot("before")
        if args.action == "inspect":
            x, y = button_center(root)
            print("已读取应用界面，未启动录音。Probe 按钮中心：%d, %d" % (x, y))
            print("界面 XML：", device.last_xml)
            return 0
        if args.action == "run":
            x, y = button_center(root)
            print("\n即将运行三路麦克风 Probe。", flush=True)
            print("全程保持安静。" if args.label == "quiet" else
                  "保持相同距离、音量，开始后连续重复同一句话，直到打印完整报告。", flush=True)
            for value in (3, 2, 1):
                print(value, flush=True)
                time.sleep(1)
            print("开始。", flush=True)
            # 倒计时中界面可能变化；重新验证应用及目标按钮，避免点击其他界面。
            root = device.snapshot("ready")
            x, y = button_center(root)
            tapped = True
            device.shell("input tap %d %d" % (x, y))
            # 不在每个 30ms 帧上查询界面，减少对测试的干扰。
            time.sleep(12)
            deadline = time.monotonic() + 35
            while True:
                root = device.snapshot("after")
                report = read_report(root)
                if report is not None:
                    break
                texts = [n.get("text", "") for n in app_nodes(root)]
                progress = next((t for t in texts if t.startswith("Testing ")), "尚未读到完成报告")
                print(progress, flush=True)
                if time.monotonic() >= deadline:
                    raise ProbeError("报告等待超时；不能把未完成测试当成结果。")
                time.sleep(2)
        else:
            report = read_report(root)
            if report is None:
                raise ProbeError("当前界面没有完整报告。read 不会主动开始录音。")
        path = folder / ("r1-probe-" + args.label + ".txt")
        path.write_text(report + "\n", encoding="utf-8")
        finished = True
        print("\n" + report)
        print("报告已保存：", path)
        if "ERROR:" in report or "init=false" in report or "recording=false" in report or "timeout=true" in report:
            print("注意：已成功导出报告，但部分测试报错；这不等于硬件验证通过。")
        return 0
    except (ProbeError, OSError) as exc:
        print("\n停止：" + str(exc), file=sys.stderr)
        print("本地诊断目录：" + str(folder), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n用户中止。", file=sys.stderr)
        return 130
    finally:
        if tapped:
            try:
                device.stop_app()
                print("已停止 Voice Satellite 应用以释放本次录音。", file=sys.stderr)
            except ProbeError as exc:
                print("未确认录音已停止：" + str(exc), file=sys.stderr)
                print("请在 ADB 恢复后停止应用；必要时给 R1 断电。", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
