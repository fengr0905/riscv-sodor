//**************************************************************************
// Scratchpad Memory (asynchronous)
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2013 Jun 12
//
// Provides a variable number of ports to the core, and one port to the HTIF
// (host-target interface).
//
// Assumes that if the port is ready, it will be performed immediately.
// For now, don't detect write collisions.
//
// Optionally uses synchronous read (default is async). For example, a 1-stage
// processor can only ever work using asynchronous memory!

package Common
{

import chisel3._
import chisel3.util._
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}
import chisel3.experimental._

import Constants._
import Common._
import Common.Util._

trait MemoryOpConstants 
{
   val MT_X  = 0.asUInt(3.W)
   val MT_B  = 1.asUInt(3.W)
   val MT_H  = 2.asUInt(3.W)
   val MT_W  = 3.asUInt(3.W)
   val MT_D  = 4.asUInt(3.W)
   val MT_BU = 5.asUInt(3.W)
   val MT_HU = 6.asUInt(3.W)
   val MT_WU = 7.asUInt(3.W)

   val M_X   = "b0".asUInt(1.W)
   val M_XRD = "b0".asUInt(1.W) // int load
   val M_XWR = "b1".asUInt(1.W) // int store
}

class Rport(val addrWidth : Int,val dataWidth : Int) extends Bundle{
   val addr = Output(UInt(addrWidth.W))
   val data = Input(UInt(dataWidth.W))
   override def cloneType = { new Rport(addrWidth,dataWidth).asInstanceOf[this.type] }
}

class Wport(val addrWidth : Int,val dataWidth : Int) extends Bundle{
   val maskWidth = dataWidth/8
   val addr = Output(UInt(addrWidth.W))
   val data = Output(UInt(dataWidth.W))
   val mask = Output(UInt(maskWidth.W))
   val en = Output(Bool())
   override def cloneType = { new Wport(addrWidth,dataWidth).asInstanceOf[this.type] }
}

class d2h2i1(val addrWidth : Int) extends Bundle{
   val dataInstr = Vec(2,new  Rport(addrWidth,32))
   val dw = new  Wport(addrWidth,32)
   val hr = new  Rport(addrWidth,64)
   val hw = new  Wport(addrWidth,64)
   val clk = Input(Clock()) 
}

class AsyncReadMem(val addrWidth : Int) extends BlackBox{
   val io = IO(new d2h2i1(addrWidth))


}

// from the pov of the datapath
class MemPortIo(data_width: Int)(implicit conf: SodorConfiguration) extends Bundle 
{
   val req    = Decoupled(new MemReq(data_width))
   val resp   = Flipped(new ValidIO(new MemResp(data_width)))
  override def cloneType = { new MemPortIo(data_width).asInstanceOf[this.type] }
}

class MemReq(data_width: Int)(implicit conf: SodorConfiguration) extends Bundle
{
   val addr = Output(UInt(conf.xprlen.W))
   val data = Output(UInt(data_width.W))
   val fcn  = Output(UInt(M_X.getWidth.W))  // memory function code
   val typ  = Output(UInt(MT_X.getWidth.W)) // memory type
  override def cloneType = { new MemReq(data_width).asInstanceOf[this.type] }
}

class MemResp(data_width: Int) extends Bundle
{
   val data = Input(UInt(data_width.W))
  override def cloneType = { new MemResp(data_width).asInstanceOf[this.type] }
}

// NOTE: the default is enormous (and may crash your computer), but is bound by
// what the fesvr expects the smallest memory size to be.  A proper fix would
// be to modify the fesvr to expect smaller sizes.
//for 1,2 and 5 stage need for combinational reads 
class AsyncScratchPadMemory(num_core_ports: Int, num_bytes: Int = (1 << 21))(implicit conf: SodorConfiguration) extends Module
{
   val io = IO(new Bundle
   {
      val core_ports = Vec(num_core_ports, Flipped(new MemPortIo(data_width = conf.xprlen)) )
      val htif_port = Flipped(new MemPortIo(data_width = 64))
   })
   val num_bytes_per_line = 8
   val num_lines = num_bytes / num_bytes_per_line
   println("\n    Sodor Tile: creating Asynchronous Scratchpad Memory of size " + num_lines*num_bytes_per_line/1024 + " kB\n")
   val async_data = Module(new AsyncReadMem(log2Ceil(num_bytes)))
   async_data.io.clk := clock
   for (i <- 0 until num_core_ports)
   {
      io.core_ports(i).resp.valid := io.core_ports(i).req.valid
      
      io.core_ports(i).req.ready := Bool(true) // for now, no back pressure 

      val req_valid      = io.core_ports(i).req.valid
      val req_addr       = io.core_ports(i).req.bits.addr
      val req_fcn        = io.core_ports(i).req.bits.fcn
      val req_typ        = io.core_ports(i).req.bits.typ
      val byte_shift_amt = io.core_ports(i).req.bits.addr(2,0)
      val bit_shift_amt  = Cat(byte_shift_amt, UInt(0,3))

      // read access
      async_data.io.dataInstr(i).addr := req_addr
      io.core_ports(i).resp.bits.data := async_data.io.dataInstr(i).data
      //io.core_ports(i).resp.bits.data := rdata_out

      // write access
      when (req_valid && req_fcn === M_XWR)
      {
         async_data.io.dw.addr := io.core_ports(i).req.bits.addr
         async_data.io.dw.data := io.core_ports(i).req.bits.data
         async_data.io.dw.en := Bool(true)
         async_data.io.dw.mask := StoreMask(req_typ, req_addr(2,0))
         // move the wdata into position on the sub-line
      }
   }  


   // HTIF -------
   val htif_idx = Reg(UInt())
   htif_idx := io.htif_port.req.bits.addr
   io.htif_port.req.ready := Bool(true) // for now, no back pressure
   io.htif_port.resp.valid := Reg(next=io.htif_port.req.valid && io.htif_port.req.bits.fcn === M_XRD)
   
   // synchronous read
   async_data.io.hr.addr := io.htif_port.req.bits.addr
   io.htif_port.resp.bits.data := async_data.io.hr.data
   when (io.htif_port.req.valid && io.htif_port.req.bits.fcn === M_XWR)
   {
      async_data.io.hw.addr := io.htif_port.req.bits.addr
      async_data.io.hw.data := io.htif_port.req.bits.data
      async_data.io.hw.en := Bool(true)
      async_data.io.hw.mask := "b11111111".U
   }   
}

class ScratchPadMemory(num_core_ports: Int, num_bytes: Int = (1 << 21), seq_read: Boolean = false)(implicit conf: SodorConfiguration) extends Module
{
   val io = IO(new Bundle
   {
      val core_ports = Vec(num_core_ports, Flipped(new MemPortIo(data_width = conf.xprlen)) )
      val htif_port = Flipped(new MemPortIo(data_width = 64))
   })


   // HTIF min packet size is 8 bytes 
   // but 32b core will access in 4 byte chunks
   // thus we will bank the scratchpad
   val num_bytes_per_line = 8
   val num_lines = num_bytes / num_bytes_per_line
   println("\n    Sodor Tile: creating Synchronous Scratchpad Memory of size " + num_lines*num_bytes_per_line/1024 + " kB\n")
   val data_bank = SyncReadMem(num_lines, Vec(num_bytes_per_line, UInt(8.W)))

   // constants
   val idx_lsb = log2Ceil(num_bytes_per_line) 

   for (i <- 0 until num_core_ports)
   {
      io.core_ports(i).resp.valid := Reg(next = io.core_ports(i).req.valid)
      
      io.core_ports(i).req.ready := Bool(true) // for now, no back pressure 

      val req_valid      = io.core_ports(i).req.valid
      val req_addr       = io.core_ports(i).req.bits.addr
      val req_data       = io.core_ports(i).req.bits.data
      val req_fcn        = io.core_ports(i).req.bits.fcn
      val req_typ        = io.core_ports(i).req.bits.typ
      val byte_shift_amt = io.core_ports(i).req.bits.addr(2,0)
      val bit_shift_amt  = Cat(byte_shift_amt, UInt(0,3))

      // read access
      val data_idx = Wire(UInt())
      data_idx := req_addr >> idx_lsb.U
      val r_data_idx = Reg(UInt(32.W))
      val read_data_out = Wire(Vec(num_bytes_per_line, UInt(8.W)))
      val rdata_out = Wire(UInt(32.W))

      read_data_out := data_bank.read(r_data_idx)
      rdata_out     := LoadDataGen(read_data_out, Reg(next=req_typ), Reg(next = req_addr(2,0)))
      io.core_ports(i).resp.bits.data := rdata_out



      // write access
      when (req_valid && req_fcn === M_XWR)
      {
         val wdata = StoreDataGen(req_data, req_typ, req_addr(2,0)) 
         data_bank.write(data_idx, wdata, StoreMask(req_typ, req_addr(2,0)).toBools)  
         // move the wdata into position on the sub-line
      }
      .elsewhen (req_valid && req_fcn === M_XRD){
         r_data_idx := data_idx
      }
   }  


   // HTIF -------
   val htif_idx = Reg(UInt())
   htif_idx := io.htif_port.req.bits.addr >> idx_lsb.U
   io.htif_port.req.ready := Bool(true) // for now, no back pressure
   io.htif_port.resp.valid := Reg(next=io.htif_port.req.valid && io.htif_port.req.bits.fcn === M_XRD)
   
   // synchronous read
   
   io.htif_port.resp.bits.data := data_bank.read(htif_idx).asUInt
   when (io.htif_port.req.valid && io.htif_port.req.bits.fcn === M_XWR)
   {
      data_bank.write(htif_idx, GenVec(io.htif_port.req.bits.data), "b11111111".U.toBools) 
   }
}

object GenVec
{
   def apply(din: UInt): Vec[UInt] = 
   {
      val dout = Wire(Vec(8, UInt(8.W)))
      dout(0) := din(7,0)
      dout(1) := din(15,8)
      dout(2) := din(23,16)
      dout(3) := din(31,24)
      dout(4) := din(39,32)
      dout(5) := din(47,40)
      dout(6) := din(55,48)
      dout(7) := din(63,56)
      return dout
   }
}

object StoreDataGen
{
   def apply(din: Bits, typ: UInt, idx: UInt): Vec[UInt] =
   {
      val word = (typ === MT_W) || (typ === MT_WU)
      val half = (typ === MT_H) || (typ === MT_HU)
      val dout = Wire(Vec(8, UInt(8.W)))
      dout(idx) := din(7,0)
      dout(idx + 1.U) := Mux(half, din(15,8), 0.U)
      dout(idx + 2.U) := Mux(word, din(23,16), 0.U)
      dout(idx + 3.U) := Mux(word, din(31,24), 0.U)
      return dout
   }
}


object StoreMask
{
   def apply(sel: UInt, idx: UInt): UInt = 
   {
      val word = (sel === MT_W) || (sel === MT_WU)
      val half = (sel === MT_H) || (sel === MT_HU)
      val wmask = Wire(UInt(8.W))
      val temp_byte = Wire(UInt(8.W))
      val temp_half = Wire(UInt(8.W))
      val temp_word = Wire(UInt(8.W))
      temp_byte :=  1.U << idx //for byte access
      temp_half := 3.U << idx
      temp_word := 15.U << idx
      wmask := Mux(word, temp_word,
               Mux(half, temp_half, temp_byte))
      return wmask
   }
}

//appropriately mask and sign-extend data for the core
object LoadDataGen
{
   def apply(data: Vec[UInt], typ: UInt, idx: UInt) : UInt =
   {
      val word = (typ === MT_W) || (typ === MT_WU)
      val half = (typ === MT_H) || (typ === MT_HU)
      val dout_7_0 = Wire(UInt(8.W))
      val dout_15_8 = Wire(UInt(8.W))
      val dout_31_16 = Wire(UInt(16.W))
      dout_7_0 := data(idx)
      dout_15_8 := Mux(half, data(idx + 1.U), 0.U)
      dout_31_16 := Mux(word, Cat(data(idx + 3.U),data(idx + 2.U)), 0.U)
      return Cat(dout_31_16,Cat(dout_15_8,dout_7_0))
   }
}

}
