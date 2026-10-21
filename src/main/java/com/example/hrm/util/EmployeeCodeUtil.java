package com.example.hrm.util;


public class EmployeeCodeUtil {


    public static String formatEmployeeNo(
            String code
    ){


        if(code==null)
            return "";


        code =
                code.trim();



        if(code.matches("^\\d\\.\\d{4}$")){

            return "0"+code;

        }


        return code;

    }






    public static String formatAttendanceCode(
            String code
    ){


        if(code==null)
            return "";



        code =
                code.trim();



        try{


            long number =
                    Long.parseLong(code);



            return String.format(
                    "%08d",
                    number
            );


        }
        catch(Exception e){


            return code;


        }


    }


}