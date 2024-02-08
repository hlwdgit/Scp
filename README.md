# ShitCP
## 说明
无固定分片大小的流传输协议。基于数据范围传输而不是分片。sn包序号不支持复用重发。
支持快速重传。
在需要的时候进行封包。比如，在特定时间，封装需要的长度的数据包，不会超过指定的包大小。
基于数据范围传输而不是固定分片大小。

环境：java21，netty

rto有问题，针对包数量的窗口没有，拥塞控制也没有。
没有连接管理，需要自己实现。需要自己封装，只有几个核心方法。
takeSend(buf,最大包长度)  需要发送时 封装需要发送的数据包。
input(收到的数据)  收到数据时传入处理。
send(buf) 要发送的数据写入到发送缓冲区。
recv(buf)  从接收缓冲区取出收到的数据。
需要自行封装使用，在需要的时间点进行发送需要长度的包。
想要kcp一样使用，还要自己设计拥塞控制和rto。
## 性能
本地测试UDP模拟能达到4.5W包 60MB/s极限。(设计问题,我只能做到这种程度了,我测试KCP-NETTY这个项目对照 那个能6w包/s)

## 适用场景
在时间和包大小上来模拟其他协议。
和普通的常规使用(如果有人用会很开心但是没什么性能优势，小数据量传输凑合。而且没设计握手和连接，两端初始不匹配会错误。)
或嵌入其他协议中使用。
比如，觉得使用kcp或其他尽管加密了但是有时频大小特征的情况。
可以试试这个，自己设计整流器在特定时间和大小发包。
理论上大概应该也许可以消除时间和大小上的特征。可以主动拟合其他加密协议或随机化消除特征。


## 缺点
未实际应用。不懂技术，心血来潮无聊写的。
无conv，无连接断开机制。仅设计了传输和ack。
发送数据和ack都是一种包。无需单独发送ack包。
没conv，需要的自己加。
没设计拥塞控制。目前只是简单的限制发出去的包的数量，达到后就概率不发了。


没有窗口，要说的话，第一个未接收的数据位置开始，rcv_buf_size大小。就是可接受的窗口。

动态包大小使用时，想实现fec困难。不过如果想动态偶尔调整包大小来实现fec倒是应该还行。
不过重传可能会将数据切的零零碎碎的。比如上次1376数据，下次附带了ackmask 相同的容量，数据就少了几字节。

rto复制的KCP的计算。而且数据包没ts字段，仅仅是本地的时间并不准确。何时发送和输入数据全由用户调用。而且这个协议并不主要关心延迟。需要的自己设计。
我不会计算rto所以复制的，而且这个协议用以模拟时间大小对rto并不敏感，甚至需要关掉。需要的自己设计rto的计算。

无法原包重发。因为每次封装的数据包大小都可能不一样，比如这次需要1400，下次整流器需要500。1400的丢包重传肯定不能直接重发1400因为这次只能发500。
之后永远只需要500，肯定无法封装超过500的。所以无法原样重发。
只能使用新的sn，填入数据尽量接近限制。

sn序号无法复用，所以长时间丢包会导致记录收到哪些包的 ackmask 越来越长，无法完整传输ackmask。
如果重发的也丢了且在ackmask之外，则只能等待超时重发。无法再次快速确认丢失重传。建议魔改成，以收到的最大sn 为末端 传输ackmask。接近超出的无法收到只能等超时重传的问题。
不过不怎么丢包的场景完全不需要考虑。

设计问题，性能差一些，达不到网上下载的kcp-netty的性能，大概勉强能接近三分之一?
瓶颈全在循环遍历上。已经尽力优化了。能力有限。

测试是在单机测试的，kcp-netty我这里每秒6w包大概能50~60M。
而我的scp写的不知道是不是代码有问题，不会设计。循环里能5秒1000M。
放到udp上极限只能100M 6秒，大概每秒只有十几M。
普通的使用应该没问题。(纠正 接近40M每秒 哦耶)(再次纠正能达到60M了 哈，虽然达不到包数量6w，只能达到4.5w左右大概)

以及send方法，虽然没限制缓冲区的大小。
写入多少都可以，但是可能存在回绕问题，大概不能超过int 4分之一还是2分之一？
一般也不会有人直接往等待发送的缓冲区里写上G的数据吧。不要一股脑把文件读入内容然后直接传进去了。会OOM。
接收的缓冲区倒是有大小限制。

rcv_buf_size目前是接收方和发送方一样大。需要的自己改，单独保存远程的rcv_buf_size如果要改接收方动态缓冲区大小的话。
## 为什么叫ShitCP
依葫芦画瓢随便命名的，设计过好几次，瞎写的。重写n次。
全是注释 输出和断点。随便命名的ZCP SCP  GCP LCP。
写着写着看不下去了最后重写了。
从范围传输 只使用rcv_offset。进行传输然后一点点加的功能。发现原来这样清晰些稀里糊涂写完了。
毫无技术含量全是时间堆的调试和输出。
简直是一坨屎山开凿出来的。算了，命名成ShitCP 发出来看看恶心一下不怕shit的人。
不过确实能用，正常运行跑起来了。性能问题主要是在标记数据范围和循环上。
因为不是写入snd缓冲区的时候进行分片，而是需要的时候才取出数据。
技术太菜已经尽力优化了。循环数组和discardReadbytes的循环使用的Buf。
不知道其他人要是设计的话，会怎么设计。我不懂数据结构设计的不好。


好比shit山开凿出来雕像
好比粪池3d打印浮现出来
历尽千辛万苦，绞尽粪坑
.......................终于！设计写出来了！........Shit C P。。。。
经过我的努力。。。屎山貌似无误的跑起来了
能用吧，应该。虽然不完美还有缺陷。


## 包结构
数据结构

cmd 1B  标记有无ackmask 有无数据  ackmask是不是压缩的
sn 4B  发送方   包的sn
una 4B  接收方  下一个要接收的sn 作为ackmask的起始位置  和确认之前所有的sn
rcv_offset 4B  接收方  已经接收到的位置
updateUna 4B  发送方 更新接收方的 更新远程 rcv_next_sn  表示之前的都被确认接收了。
ackmaskLen 2B 需要时        接收方ackmask的长度
ackmask  需要时        接收方告知发送方  哪些包收到了，如果存在持续丢包，区间丢包 没被反馈到，只能等待超时重传
payloads  需要时    发送方 携带的数据段落 可以携带多个部分。

第一个payload  
基础offset4B len2B +数据    发送方 数据的开始位置和长度

其余的
相较于前一个末端位置 偏移量
offset2B len2b +数据


## 吐槽
万万没想的啊，设计的这么糟糕，居然性能都在循环上。
循环上，循环上！
完全不知道怎么优化。现在只是勉强能用的程度。
完全没什么应用场景。性能不好。

最初以为使用varint或少量的字段，就能实现。
写着写着一点点实现和增加。发现不能。。。

写这段话的时候突然发现有一个地方的方法忘了
循环里的忘了。
替换优化后的了。。。。
虽然替换后应该也提升不了多少。
不管了，只是发出来看看有没有人感兴趣。(ackSndWithRmtRcvOffset的ackSn 换成 ackSeg(cur))
还在挂着跑UDP本机测试。所以懒得动了。已经跑了几天了，没遇到问题。
以后的不会发了。个人不喜欢开源。虽然设计的很糟糕。

也不懂什么开源协议，如果有人感兴趣魔改或使用了。
留个言告诉我一声。好无聊=_=、

本项目无限制，可以随意使用和修改。
欢迎阅翔。

....测试暂停了那句我加上发现。性能上去了一些。
UDP本机持续测试，100M，最快2秒6.。性能接近了。0—0
接近40M每秒。能用！

好吧，增加了接收缓冲区大小再测试。发现3W是因为取不出来数据没发送。而不是积压input处理能力不足。
最快1.6秒。约60M1秒。(哈)
不过好像有什么不对。接收缓冲区40M时测试的。(改成10M发现一样)
实际使用不可能遇到吧，而且实际使用应该也不会使用到60M每秒的速度。
只是单机测试。

实际使用还需要 设计 拥塞控制和rto 以及动态缓冲区大小。
主要是这个设计有问题，目前是根据  第一个未确认为开始，远方接收缓冲区大小的范围内进行发送。超过的不会发。
不过不这样限制继续发送，可能会无限填充接收方缓冲区的问题。
